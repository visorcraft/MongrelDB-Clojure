(ns basic-crud
  "Basic CRUD: create a table, insert, query, update, and delete.

  Run with:
    clojure -M:examples -m basic-crud"
  (:require [visorcraft.mongreldb.core :as mdb])
  (:import [java.util UUID]))

(defn -main [& _args]
  (let [db (mdb/connect)
        table (str "clj_crud_" (.toString (UUID/randomUUID)))]
    (println "Health:" (mdb/health db))
    (mdb/create-table db table
                      [{"id" 1 "name" "id" "ty" "int64" "primary_key" true "nullable" false}
                       {"id" 2 "name" "label" "ty" "string" "primary_key" false "nullable" false}])
    (println "Created table:" table)
    (mdb/put db table {1 1, 2 "first"})
    (mdb/put db table {1 2, 2 "second"})
    (println "Count after puts:" (mdb/count* db table))
    (mdb/delete-by-pk db table 2)
    (println "Count after delete:" (mdb/count* db table))
    (mdb/drop-table db table)
    (println "Dropped table:" table)))
