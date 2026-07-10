(ns visorcraft.mongreldb.transaction
  "Stage operations locally and commit them atomically in a single `/kit/txn`
  request.

  The engine enforces unique, foreign-key, check, and trigger constraints at
  commit time; on any violation all operations roll back and `commit` throws a
  `dev.visorcraft.mongreldb.ConflictException` carrying the server's structured
  error code and offending op index.

  A transaction is single-use: after `commit` or `rollback` it must not be
  reused. Calling `commit` or `rollback` a second time throws an
  `IllegalStateException`.

  Usage:

      (require '[visorcraft.mongreldb.core :as mdb])
      (let [txn (mdb/begin db)]
        (put txn \"orders\" {1 10, 2 \"Dave\"})
        (put txn \"orders\" {1 11, 2 \"Eve\"})
        (delete-by-pk txn \"orders\" 2)
        (commit txn) ;; atomic - all or nothing
        )"
  (:require [visorcraft.mongreldb.core :as core]))

(def ^:const already-committed
  "Error message thrown when a transaction is committed or rolled back twice."
  "mongreldb: transaction already committed")

(defn- ensure-open [{:keys [committed]}]
  (when committed
    (throw (IllegalStateException. already-committed))))

(defn begin
  "Construct a new transaction bound to `client`."
  [client]
  {:client    client
   :ops       []
   :committed false})

(defn put
  "Stage an insert. `cells` is a column-id-to-value map, flattened to the
  server's [col_id, value, ...] array before sending. `returning`, when true,
  asks the daemon to echo the row in the per-operation result."
  ([txn table cells] (put txn table cells false))
  ([txn table cells returning]
   (ensure-open txn)
   (let [inner {:table table :cells (core/flatten-cells cells) :returning (boolean returning)}]
     (update txn :ops conj {:put inner}))))

(defn upsert
  "Stage an insert-or-update. `update-cells`, when non-nil, supplies the values
  written on a primary-key conflict; nil means DO NOTHING."
  ([txn table cells] (upsert txn table cells nil false))
  ([txn table cells update-cells] (upsert txn table cells update-cells false))
  ([txn table cells update-cells returning]
   (ensure-open txn)
   (let [inner (cond-> {:table     table
                        :cells     (core/flatten-cells cells)
                        :returning (boolean returning)}
                 (some? update-cells) (assoc :update_cells (core/flatten-cells update-cells)))]
     (update txn :ops conj {:upsert inner}))))

(defn delete
  "Stage a delete by the internal row id."
  [txn table row-id]
  (ensure-open txn)
  (update txn :ops conj {:delete {:table table :row_id row-id}}))

(defn delete-by-pk
  "Stage a delete by primary-key value."
  [txn table pk]
  (ensure-open txn)
  (update txn :ops conj {:delete_by_pk {:table table :pk pk}}))

(defn count*
  "Return the number of staged operations."
  [txn]
  (count (:ops txn)))

(defn commit
  "Send all staged operations atomically and return the per-operation results.

  `idempotency-key`, when non-nil and non-empty, makes the commit safe to retry
  - the daemon returns the original response on duplicate commits, even after a
  crash."
  ([txn] (commit txn nil))
  ([{:keys [client] :as txn} idempotency-key]
   (when (:committed txn)
     (throw (IllegalStateException. already-committed)))
   (let [txn' (assoc txn :committed true)]
     (if (empty? (:ops txn))
       {:txn txn' :results []}
       {:txn txn' :results (core/commit-txn client (:ops txn) idempotency-key)}))))

(defn rollback
  "Discard all staged operations."
  [{:keys [committed] :as txn}]
  (when committed
    (throw (IllegalStateException. already-committed)))
  (-> txn (assoc :ops []) (assoc :committed true)))
