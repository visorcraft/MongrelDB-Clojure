(ns query-builder-example
  "Demonstrate the fluent query builder: range and PK predicates, projection,
  and limit.

  Run with:
    clojure -M:examples -m query-builder-example"
  (:require [visorcraft.mongreldb.core :as mdb]
            [visorcraft.mongreldb.query :as q])
  (:import [java.util UUID]))

(defn- cell-value [cells col-id]
  (when (vector? cells)
    (loop [xs cells]
      (when (seq xs)
        (let [[id val] xs]
          (if (= id col-id) val (recur (nnext xs))))))))

(defn -main [& _args]
  (let [db (mdb/connect)
        table (str "clj_qb_" (.toString (UUID/randomUUID)))]
    (mdb/create-table db table
                      [{"id" 1 "name" "id" "ty" "int64" "primary_key" true "nullable" false}
                       {"id" 2 "name" "amount" "ty" "int64" "primary_key" false "nullable" false}])
    (doseq [i (range 1 6)]
      (mdb/put db table {1 i, 2 (* i 100)}))
    (println "Inserted 5 rows into" table)
    (let [[rows] (-> (mdb/query db table)
                     (q/where "range" {:column 2 :min 200 :max 400})
                     (q/execute-full))]
      (println "Range query [200,400] matched" (count rows) "rows:")
      (doseq [r rows]
        (println "  pk =" (cell-value (:cells r) 1)
                 "amount =" (cell-value (:cells r) 2))))
    (let [[rows] (-> (mdb/query db table)
                     (q/where "pk" {:value 3})
                     (q/execute-full))]
      (println "PK=3 query matched" (count rows) "rows"))
    (let [[rows truncated] (-> (mdb/query db table)
                               (q/projection [1])
                               (q/limit 2)
                               (q/execute-full))]
      (println "Limit-2 projection matched" (count rows) "rows, truncated =" truncated))
    (mdb/drop-table db table)
    (println "Dropped table:" table)))
