# Transactions

MongrelDB commits every write through a single atomic transaction endpoint
(`POST /kit/txn`). This guide covers the two ways to use it - a one-shot
single op, and a staged batch - plus idempotency keys for safe retries, typed
constraint-violation handling, and rollback.

The engine enforces `UNIQUE`, foreign-key, check, and trigger constraints at
**commit time**. A violation aborts the entire batch: no op in the batch
becomes visible.

---

## Single puts vs. batch transactions

### Single op: `put`

`(mdb/put db table cells)` is a convenience wrapper that sends a one-op
transaction. Use it when a write is independent and you do not need atomicity
across multiple rows.

```clojure
;; One row, one atomic op. nil means "no idempotency key".
(let [res (mdb/put db "orders" {1 1, 2 "Alice", 3 99.5})]
  (println res))
```

`upsert`, `delete`, and `delete-by-pk` are the same shape: single-op
transactions.

### Batch: `begin` + `commit`

When several writes must succeed or fail together, stage them on a
transaction and commit once. All ops go to the server in a single HTTP
request and commit atomically.

```clojure
(require '[visorcraft.mongreldb.transaction :as txn])

(let [t (-> (mdb/begin db)
            (txn/put "orders" {1 10, 2 "Dave", 3 50.0})
            (txn/put "orders" {1 11, 2 "Eve",  3 75.0})
            (txn/delete-by-pk "orders" 2))
      {:keys [results]} (txn/commit t)]
  (println "committed" (count results) "ops"))
```

The `returning` flag on `txn/put` asks the daemon to echo the written row back
in the result - useful for reading server-assigned values.

```clojure
(let [t (-> (mdb/begin db)
            (txn/put "orders" {1 42, 2 "Hal", 3 12.0} true))
      {:keys [results]} (txn/commit t)]
  (println "server echoed:" (first results)))
```

`(txn/upsert txn table cells update-cells)` applies `update-cells` on a
primary-key conflict. A `nil` `update-cells` means "do nothing on conflict".

## Idempotency keys for safe retries

Networks drop requests and daemons crash after committing but before replying.
An idempotency key makes a commit safe to retry: the daemon remembers the key
and replays the **original** result on a duplicate commit, even across
restarts.

Pass the key as the second argument to `commit` (or `put`/`upsert`):

```clojure
;; A web handler that must not double-charge, even if the client retries or the
;; connection drops after the daemon committed.
(defn charge [db order-id]
  ;; Use a stable, business-meaningful key derived from the request. On a retry
  ;; with the same key the daemon returns the first commit's result instead of
  ;; inserting a second row.
  (let [t (-> (mdb/begin db)
              (txn/put "charges" {1 order-id, 2 199.0}))]
    (txn/commit t (str "charge:" order-id))))
```

Rules for keys:

- Any non-empty string works. Prefer content-derived, globally-unique values
  (e.g. `(str "charge:" order-id)`).
- `nil` (the default) disables idempotency - a retry will commit again.
- The key scopes the **entire batch**, not individual ops. Reuse the exact
  same ops and key together when retrying.

A safe retry loop:

```clojure
(defn commit-with-retry [db build-txn key max-attempts]
  (loop [attempt 0]
    ;; Build a fresh transaction inside the loop so retries always start clean.
    (let [t (build-txn db)]
      (try
        (txn/commit t key)
        (catch dev.visorcraft.mongreldb.ConflictException e
          (throw e)) ;; not transient - surface to the caller
        (catch dev.visorcraft.mongreldb.MongrelDBException e
          ;; QueryException / network - the idempotency key makes it safe to retry.
          (if (= attempt (dec max-attempts))
            (throw e)
            (do (Thread/sleep (bit-shift-left 1 attempt))
                (recur (inc attempt)))))))))
```

Build the transaction inside the retry loop so a failed `commit` (which flips
the transaction to "committed") is replaced by a fresh one carrying the same
ops and the same key.

## Handling constraint violations

Constraint violations arrive as HTTP 409, mapped to `ConflictException`. It
carries the structured code and the offending op index:

```clojure
(try
  (let [t (-> (mdb/begin db)
              (txn/put "orders" {1 1}))]  ;; duplicate PK
    (txn/commit t))
  (catch dev.visorcraft.mongreldb.ConflictException e
    (case (.code e)
      "UNIQUE_VIOLATION"  (println "duplicate at op" (.opIndex e) ":" (.getMessage e))
      "FK_VIOLATION"      (println "missing parent at op" (.opIndex e))
      "CHECK_VIOLATION"   (println "check failed at op" (.opIndex e))
      (println "other conflict:" (.getMessage e)))))
```

The error envelope from the daemon looks like:

```json
{"status": "aborted", "error": {"code": "UNIQUE_VIOLATION", "message": "...", "op_index": 0}}
```

`op_index` points at the offending op within the batch so you can report which
row caused the failure.

## Rollback after failure

There are two notions of "rollback":

1. **Server-side.** When `commit` throws `ConflictException`, the engine has
   already discarded the entire batch. Nothing was written; there is no server
   rollback to perform.
2. **Client-side.** `txn/rollback` clears the locally staged ops. Call it to
   release the transaction when you decide not to commit (for example, after
   a validation error in your own code, before ever sending).

```clojure
(let [t (-> (mdb/begin db)
            (txn/put "orders" {1 1, 2 "Iris", 3 5.0}))]
  (if (not (business-rule-ok))
    ;; Throw the staged ops away locally. Nothing has been sent to the daemon.
    (txn/rollback t)
    (try
      (txn/commit t)
      (catch dev.visorcraft.mongreldb.ConflictException _
        ;; On conflict the server already rolled back; nothing more to do.
        ))))
```

`rollback` and `commit` both throw `IllegalStateException` if the transaction
was already committed. Treat that as a programming error to fix upstream, not
a runtime condition to silence.

## Summary

| Goal | Use |
|------|-----|
| One independent write | `put` / `upsert` / `delete` / `delete-by-pk` |
| Several writes that must commit together | `begin` + `commit` |
| Retry safely after a network blip | `commit` with a stable key |
| Distinguish constraint classes | catch `ConflictException`, read `.code` and `.opIndex` |
| Abort before sending | `rollback` |

See [errors.md](errors.md) for the full error hierarchy and [queries.md](queries.md)
for read patterns.
