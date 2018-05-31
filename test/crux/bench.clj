(ns crux.bench
  (:require [criterium.core :as crit]
            [crux.codecs :as c]
            [crux.core :as crux]
            [crux.doc :as doc]
            [crux.fixtures :as f :refer [*kv* random-person]]
            [crux.kv :as cr]
            [crux.kv-store :as ks]
            [crux.query :as q])
  (:import java.util.Date))

(def queries {:name '{:find [e]
                      :where [[e :name "Ivan"]]}
              :multiple-clauses '{:find [e]
                                  :where [[e :name "Ivan"]
                                          [e :last-name "Ivanov"]]}
              :join '{:find [e2]
                      :where [[e :last-name "Ivanov"]
                              [e :last-name name1]
                              [e2 :last-name name1]]}
              :range '{:find [e]
                       :where [[e :age age]
                               (> age 20)]}})

(defn- insert-data [n batch-size ts index]
  (doseq [[i people] (map-indexed vector (partition-all batch-size (take n (repeatedly random-person))))]
    (case index
      :kv
      (cr/-put *kv* people ts)

      :doc
      (do (doc/store-docs *kv* people)
          (doc/store-txs *kv*
                         (vec (for [person people]
                                [:crux.tx/put
                                 (keyword (:crux.kv/id person))
                                 (str (doc/doc->content-hash person))]))
                         ts
                         (inc i))))))

(defn- perform-query [ts query index]
  (let [q (query queries)
        db-fn (fn [] (case index
                       :kv
                       (crux/as-of (crux/db *kv*) ts)

                       :doc
                       (doc/map->DocDatasource {:kv *kv*
                                                :transact-time ts
                                                :business-time ts})))]
    ;; Assert this query is in good working order first:
    (assert (pos? (count (q/q (db-fn) q))))

    (let [db (db-fn)]
      (ks/iterate-with
       *kv*
       (fn [_]
         (q/q db q))))))

(defn- do-benchmark [ts samples index quick query]
  (-> (if quick
        (crit/quick-benchmark
         (perform-query ts query index) {:samples samples})
        (crit/benchmark
         (perform-query ts query index) {:samples samples}))
      :mean
      first
      (* 1000) ;; secs -> msecs
      ))

(defn bench
  [& {:keys [n batch-size ts query samples kv index quick]
      :or {n 1000
           batch-size 10
           samples 100 ;; should always be >2
           query :name
           ts (Date.)
           kv :rocks
           index :kv
           quick true}}] ;; use Criterion's faster but "less rigorous" quick-benchmark
  ((case kv
     :rocks f/with-rocksdb
     :lmdb f/with-lmdb
     :mem f/with-memdb)
   (fn []
     (f/with-kv-store
       (fn []
         (let [insert-time (->
                            (with-out-str (time
                                           (insert-data n batch-size ts index)))
                            (clojure.string/split #" ")
                            (nth 2)
                            read-string)
               queries-to-bench (if (= query :all)
                                  (keys queries)
                                  (flatten [query]))]
           (merge {:insert insert-time}
                  (zipmap
                   queries-to-bench
                   (map (partial do-benchmark ts samples index quick)
                        queries-to-bench)))))))))

;; Datomic: 100 queries against 1000 dataset = 40-50 millis

;; ~500 mills for 1 million
(defn bench-encode [n]
  (let [d (java.util.Date.)]
    (doseq [_ (range n)]
      (c/encode cr/frame-index-eat {:index :eat :eid (rand-int 1000000) :aid (rand-int 1000000) :ts d}))))

;; ~900 ms for 1 million
;; TODO: add new test here, the value frames have been replaced by nippy.
#_(defn bench-decode [n]
    (let [f (cr/encode cr/frame-value-eat {:type :string :v "asdasd"})]
      (doseq [_ (range n)]
        (crux.codecs/decode cr/frame-value-eat f))))
