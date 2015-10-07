(ns onyx.state.log.bookkeeper
  (:require [onyx.log.curator :as curator]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [com.stuartsierra.component :as component]
            [clojure.core.async :refer  [chan go >! <! <!! >!! alts!! close!]]
            [onyx.compression.nippy :as nippy]
            [onyx.extensions :as extensions]
            [onyx.monitoring.measurements :refer [emit-latency-value emit-latency]]
            [onyx.state.core :as state]
            [onyx.peer.operation :as operation]
            [onyx.state.state-extensions :as state-extensions]
            [onyx.types :refer [inc-count! dec-count!]]
            [onyx.log.replica]
            [onyx.log.commands.assign-bookkeeper-log-id]
            [onyx.log.zookeeper :as zk]
            [onyx.static.default-vals :refer [arg-or-default defaults]])
  (:import [org.apache.bookkeeper.client LedgerHandle BookKeeper BookKeeper$DigestType AsyncCallback$AddCallback]
           [org.apache.bookkeeper.conf ClientConfiguration]
           [org.apache.curator.framework CuratorFramework CuratorFrameworkFactory]))

(defn open-ledger ^LedgerHandle [client id digest-type password]
  (.openLedger client id digest-type password))

(defn create-ledger ^LedgerHandle [client ensemble-size quorum-size digest-type password]
  (.createLedger client ensemble-size quorum-size digest-type password))

(defn bookkeeper
  ([opts]
   (bookkeeper (:zookeeper/address opts)
               (zk/ledgers-path (:onyx/id opts))
               (arg-or-default :onyx.bookkeeper/timeout opts)))
  ([zk-addr zk-root-path timeout]
   (let [conf (doto (ClientConfiguration.)
                (.setZkServers zk-addr)
                (.setZkTimeout timeout)
                (.setZkLedgersRootPath zk-root-path))]
     (BookKeeper. conf))))

(def digest-type 
  (BookKeeper$DigestType/MAC))

(defn password [peer-opts]
  (.getBytes (arg-or-default :onyx.bookkeeper/ledger-password peer-opts)))

(defrecord BookKeeperLog [client ledger-handle])

;; TODO: add zk-timeout for bookkeeper
(defmethod state-extensions/initialise-log :bookkeeper [log-type {:keys [onyx.core/replica onyx.core/peer-opts
                                                                         onyx.core/job-id onyx.core/task-id
                                                                         onyx.core/kill-ch onyx.core/task-kill-ch
                                                                         onyx.core/outbox-ch] :as event}] 
  (let [bk-client (bookkeeper peer-opts)
        ensemble-size (arg-or-default :onyx.bookkeeper/ledger-ensemble-size peer-opts)
        quorum-size (arg-or-default :onyx.bookkeeper/ledger-quorum-size peer-opts)
        ledger-handle (create-ledger bk-client ensemble-size quorum-size digest-type (password peer-opts))
        slot-id (state/peer-slot-id event)
        new-ledger-id (.getId ledger-handle)] 
    (>!! outbox-ch
         {:fn :assign-bookkeeper-log-id
          :args {:job-id job-id
                 :task-id task-id
                 :slot-id slot-id
                 :ledger-id new-ledger-id}})
    (while (and (first (alts!! [kill-ch task-kill-ch] :default true))
                (not= new-ledger-id
                      (last (get-in @replica [:state-logs job-id task-id slot-id]))))
      (info "New ledger id has not been published yet. Backing off.")
      (Thread/sleep (arg-or-default :onyx.bookkeeper/ledger-id-written-back-off peer-opts))) 
    (info "Ledger id published.")
    (->BookKeeperLog bk-client ledger-handle)))

(defn default-state 
  "Default state function. Resolves window late for perf."
  [state window]
  (if state
    state
    ((:window/agg-init window) window)))

(defn playback-windows-extents [state entry windows]
  (let [id->log-resolve (into {} 
                              (map (juxt :window/id :window/log-resolve) 
                                   windows))] 
    (reduce (fn [state' [window-entries {:keys [window/id] :as window}]]
              (reduce (fn [state'' [extent entry message-id]]
                        (update-in state'' 
                                   [id extent]
                                   (fn [ext-state] 
                                     (let [ext-state' (default-state ext-state window)
                                           apply-fn (id->log-resolve id)] 
                                       (assert apply-fn (str "Apply fn does not exist for window-id " id))
                                       (apply-fn ext-state' entry)))))
                      state'
                      window-entries)) 
            state
            (map list (rest entry) windows))))

(defn playback-ledgers [bk-client peer-opts state ledger-ids {:keys [onyx.core/windows] :as event}]
  (let [pwd (password peer-opts)] 
    (reduce (fn [st ledger-id]
              ;; TODO: Do I need to deal with recovery exception in here?
              ;; It may be better to just let the thing crash and retry
              (let [lh (open-ledger bk-client ledger-id digest-type pwd)]
                (try
                  (let [last-confirmed (.getLastAddConfirmed lh)
                        _ (info "Opened ledger:" ledger-id "last confirmed:" last-confirmed)]
                    (if (pos? last-confirmed)
                      (let [entries (.readEntries lh 0 last-confirmed)] 
                        (if (.hasMoreElements entries)
                          (loop [st-loop st element (.nextElement entries)]
                            (let [entry-val (nippy/window-log-decompress (.getEntry element))
                                  unique-id (first entry-val)
                                  st-loop' (let [st (playback-windows-extents st-loop entry-val windows)]
                                             (if unique-id
                                               (update st :filter state-extensions/apply-filter-id event unique-id)
                                               st))] 
                              (info "Played back entries for message with id: " unique-id)
                              (if (.hasMoreElements entries) 
                                (recur st-loop' (.nextElement entries))
                                st-loop')))
                          st))  
                      st))
                  (finally
                    (.close lh)))))
            state
            ledger-ids))) 

(defmethod state-extensions/playback-log-entries onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client] :as log} 
   {:keys [onyx.core/monitoring onyx.core/replica 
           onyx.core/peer-opts onyx.core/job-id onyx.core/task-id] :as event} 
   state]
  (emit-latency :window-log-playback 
                monitoring
                (fn [] 
                  (let [slot-id (state/peer-slot-id event)
                        ;; Don't play back the final ledger id because we just created it
                        prev-ledger-ids (butlast (get-in @replica [:state-logs job-id task-id slot-id]))]
                    (info "Playing back ledgers for" job-id task-id slot-id "ledger-ids" prev-ledger-ids)
                    (playback-ledgers client peer-opts state prev-ledger-ids event)))))

(defmethod state-extensions/close-log onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [client ledger-handle]} event] 
  (.close ^LedgerHandle ledger-handle)
  (.close client))

(def HandleWriteCallback
  (reify AsyncCallback$AddCallback
    (addComplete [this rc lh entry-id ack-fn]
      (ack-fn))))

(defmethod state-extensions/store-log-entry onyx.state.log.bookkeeper.BookKeeperLog
  [{:keys [ledger-handle]} event ack-fn entry]
  ;; Interesting post on latency in bookkeeper
  ;; http://mail-archives.apache.org/mod_mbox/bookkeeper-user/201509.mbox/%3CCAO2yDyaeF6T8Zza0G=BHccWeGceawYL+5TocRe40_wD6AQdFdg@mail.gmail.com%3E
  (.asyncAddEntry ^LedgerHandle ledger-handle 
                  (nippy/window-log-compress entry)
                  HandleWriteCallback
                  ack-fn))
