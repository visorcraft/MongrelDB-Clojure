(ns transactions-example
  "Demonstrate atomic batch transactions with idempotency keys.

  Run with:
    clojure -M:examples -m transactions-example"
  (:require [visorcraft.mongreldb.core :as mdb]
            [visorcraft.mongreldb.transaction :as txn])
  (:import [java.util UUID]))

(defn -main [& _args]
  (let [db (mdb/connect)
        table (str "clj_txn_" (.toString (UUID/randomUUID)))]
    (mdb/create-table db table
                      [{"id" 1 "name" "id" "ty" "int64" "primary_key" true "nullable" false}
                       {"id" 2 "name" "label" "ty" "string" "primary_key" false "nullable" false}])
    ;; Stage three inserts and commit them atomically.
    (let [{:keys [results]}
          (-> (mdb/begin db)
              (txn/put table {1 1, 2 "alpha"})
              (txn/put table {1 2, 2 "beta"})
              (txn/put table {1 3, 2 "gamma"})
              (txn/commit))]
      (println "Committed" (count results) "ops; count now" (mdb/count* db table)))
    ;; Idempotent retry with the same key must not duplicate rows.
    (let [idem-key (str "seed-" (System/currentTimeMillis))]
      (-> (mdb/begin db)
          (txn/put table {1 10, 2 "seed"})
          (txn/commit idem-key))
      (try
        (-> (mdb/begin db)
            (txn/put table {1 10, 2 "seed"})
            (txn/commit idem-key))
        (catch Exception e
          (println "Retry produced:" (.getMessage e))))
      (println "After idempotent retry, count =" (mdb/count* db table)))
    (mdb/drop-table db table)
    (println "Dropped table:" table)))
