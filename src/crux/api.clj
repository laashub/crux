(ns crux.api
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [crux.bootstrap :as bootstrap]
            [crux.db :as db]
            [crux.doc :as doc]
            [crux.http-server :as srv]
            [crux.index :as idx]
            [crux.kv-store :as ks]
            [crux.query :as q]
            [crux.tx :as tx]
            [org.httpkit.client :as http])
  (:import [java.io Closeable IOException]
           crux.query.QueryDatasource))

(defprotocol CruxDatasource
  (entity [this eid]
    "returns the entity document")
  (entity-tx [this eid]
    "returns the entity tx for an entity")
  (new-snapshot [this]
    "returns a new snapshot for q, allowing lazy results in a with-open block")
  (q [this q] [this snapshot q]
    "queries the db"))

(extend-protocol CruxDatasource
  QueryDatasource
  (entity [this eid]
    (q/entity this eid))

  (entity-tx [this eid]
    (q/entity-tx this eid))

  (new-snapshot [this]
    (ks/new-snapshot (:kv this)))

  (q [this q]
    (q/q this q))

  (q [this snapshot q]
    (q/q this snapshot q)))

(defprotocol CruxSystem
  (status [this]
    "returns the status of this node")
  (db [this] [this business-time] [this business-time transact-time]
    "returns a db for the system")
  (history [this eid]
    "returns the transaction history of an entity")
  (document [this content-hash]
    "reads a document from the document store")
  (submit-tx [this tx-ops]
    "writes the transactions to the log for processing")
  (submitted-tx-updated-entity? [this submitted-tx eid]
    "checks if a submitted tx did update an entity"))

(defrecord LocalNode [close-promise underlying options]
  CruxSystem
  (status [_]
    (srv/status-map (:kv-store @underlying) (:bootstrap-servers options)))

  (db [_]
    (q/db (:kv-store @underlying)))

  (db [_ business-time]
    (q/db (:kv-store @underlying) business-time))

  (db [_ business-time transact-time]
    (q/db (:kv-store @underlying) business-time transact-time))

  (history [_ eid]
    (doc/entity-history (:kv-store @underlying) eid))

  (document [_ content-hash]
    (let [kv (:kv-store @underlying)
          object-store (doc/->DocObjectStore kv)]
      (with-open [snapshot (ks/new-snapshot kv)]
        (get (db/get-objects object-store snapshot [content-hash]) content-hash))))

  (submit-tx [_ tx-ops]
    (db/submit-tx (:tx-log @underlying) tx-ops))

  (submitted-tx-updated-entity? [_ submitted-tx eid]
    (q/submitted-tx-updated-entity? (:kv-store @underlying) submitted-tx eid))

  Closeable
  (close [_] (deliver close-promise true)))

(defn ^Closeable start-local-node
  [start-fn options]
  (log/info "running crux in library mode")
  (let [underlying (atom nil)
        close-promise (promise)
        started-promise (promise)
        options (merge bootstrap/default-options options)
        running-future
        (future
          (log/info "crux thread intialized")
          (bootstrap/start-system
           options
           (fn with-system-callback [system]
             (deliver started-promise true)
             (log/info "crux system start completed")
             (reset! underlying system)
             @close-promise
             (log/info "starting teardown of crux system")))
          (log/info "crux system completed teardown"))]
    (while (not (or (deref started-promise 100 false)
                    (deref running-future 100 false))))
    (->LocalNode close-promise underlying options)))

(defn- api-post-sync [url body]
  (let [{:keys [body error status]
         :as result} @(http/post url {:body (pr-str body)
                                      :as :text})]
    (cond
      error
      (throw error)

      (not= 200 status)
      (throw (IOException. (str "HTTP Status " status ": " body)))

      :else
      (edn/read-string body))))

(defn- enrich-entity-tx [entity-tx]
  (some->  entity-tx
           (idx/map->EntityTx)
           (update :eid idx/new-id)
           (update :content-hash idx/new-id)))

(defrecord RemoteDatasource [url business-time transact-time]
  CruxDatasource
  (entity [this eid]
    (api-post-sync (str url "/entity") {:eid eid
                                        :business-time business-time
                                        :transact-time transact-time}))

  (entity-tx [this eid]
    (enrich-entity-tx (api-post-sync (str url "/entity-tx") {:eid eid
                                                             :business-time business-time
                                                             :transact-time transact-time})))

  (new-snapshot [this]
    (throw (UnsupportedOperationException.)))

  (q [this q]
    (api-post-sync (str url "/q") (assoc q
                                         :business-time business-time
                                         :transact-time transact-time)))

  (q [this snapshot q]
    (throw (UnsupportedOperationException.))))

(defrecord RemoteApiConnection [url]
  CruxSystem
  (status [_]
    (let [{:keys [error body]} @(http/get url)]
      (if error
        (throw error)
        (edn/read-string body))))

  (db [_]
    (->RemoteDatasource url nil nil))

  (db [_ business-time]
    (->RemoteDatasource url business-time nil))

  (db [_ business-time transact-time]
    (->RemoteDatasource url business-time transact-time))

  (history [_ eid]
    (->> (api-post-sync (str url "/history") eid)
         (mapv enrich-entity-tx)))

  (document [_ content-hash]
    (api-post-sync (str url "/document") (str content-hash)))

  (submit-tx [_ tx-ops]
    (tx/map->SubmittedTx (api-post-sync (str url "/tx-log") tx-ops)))

  (submitted-tx-updated-entity? [this {:keys [transact-time tx-id] :as submitted-tx} eid]
    (= tx-id (:tx-id (entity-tx (db this transact-time transact-time) eid))))

  Closeable
  (close [_]))

(defn ^Closeable new-api-client [url]
  (->RemoteApiConnection url))