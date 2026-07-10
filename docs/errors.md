# Error handling

Every non-2xx response from the daemon is mapped to a typed Java exception.
This is the complete reference: the exception hierarchy, the HTTP-status
mapping, the daemon's error envelope, and recovery patterns for each
category.

---

## The error model

All client errors descend from `dev.visorcraft.mongreldb.MongrelDBException`.
The client throws a specific subclass for each failure category:

| Class | Meaning | Typical cause |
|-------|---------|---------------|
| `MongrelDBException` | Base class for all client errors | (catch this to handle any failure) |
| `AuthException` | HTTP 401 or 403 | Missing/bad credentials against an auth-enabled daemon |
| `NotFoundException` | HTTP 404 | Missing table, schema, or resource |
| `ConflictException` | HTTP 409 | Unique, foreign-key, check, or trigger violation at commit |
| `QueryException` | HTTP 400 or 5xx, plus network | Malformed request, server failure, transport error |

Every exception carries three accessors:

| Method | Meaning |
|--------|---------|
| `(.status e)` | The HTTP status code, or -1 when unknown |
| `(.code e)` | The server's structured error code (e.g. `"UNIQUE_VIOLATION"`); `null` when absent |
| `(.opIndex e)` | The offending op index within a batch, when reported; `null` otherwise |

## The daemon's error envelope

```json
{
  "status": "aborted",
  "error": {
    "code": "UNIQUE_VIOLATION",
    "message": "duplicate key in column 1",
    "op_index": 0
  }
}
```

Structured codes you will commonly see in `(.code e)`:

| code | Meaning |
|------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource (table, schema) does not exist |

## HTTP status -> exception mapping

| HTTP status | Exception | Notes |
|-------------|-----------|-------|
| 401, 403 | `AuthException` | Bad/missing credentials |
| 404 | `NotFoundException` | Resource not found |
| 409 | `ConflictException` | Constraint violation at commit |
| 400 | `QueryException` | Malformed request / bad query |
| 5xx | `QueryException` | Daemon-side failure |
| other non-2xx | `QueryException` | Catch-all |
| 2xx | (no error) | Success |

Network and encoding problems (`java.io.IOException`, interrupted requests,
etc.) are also mapped to `QueryException`.

## Discriminating errors

### By category - catch the subclass

```clojure
(try
  (mdb/schema-for db "missing_table")
  (catch dev.visorcraft.mongreldb.NotFoundException _
    (println "table does not exist"))
  (catch dev.visorcraft.mongreldb.ConflictException _
    (println "unexpected conflict on a read"))
  (catch dev.visorcraft.mongreldb.AuthException _
    (println "bad credentials"))
  (catch dev.visorcraft.mongreldb.QueryException e
    (println "server error or malformed request:" (.getMessage e)))
  (catch dev.visorcraft.mongreldb.MongrelDBException e
    (println "other error:" (.getMessage e))))
```

### By details - read the accessors

```clojure
(try
  (txn/commit t)
  (catch dev.visorcraft.mongreldb.ConflictException e
    (println "status=409 code=" (.code e) "op=" (.opIndex e) "msg=" (.getMessage e))))
```

## Recovery patterns

### Auth failure - do not retry blindly

A retry will not fix bad credentials. Surface the error to the caller or
operator.

```clojure
(catch dev.visorcraft.mongreldb.AuthException e
  (throw (ex-info "credentials rejected; refresh token" {:cause e})))
```

### Not found - fall back, do not crash

For lookups by primary key, a 404 may be a normal "absent" result.

```clojure
(try
  (mdb/schema-for db table-name)
  (catch dev.visorcraft.mongreldb.NotFoundException _
    {})) ;; table missing - treat as empty
```

Note: a `pk` query against an existing table returns zero rows, not a 404;
`NotFoundException` here means the table itself is missing.

### Constraint conflict - report the offending op

```clojure
(try
  (txn/commit t)
  (catch dev.visorcraft.mongreldb.ConflictException e
    (if (.opIndex e)
      (println "op" (.opIndex e) "violated" (.code e) ":" (.getMessage e))
      (println "conflict" (.code e) ":" (.getMessage e)))
    (throw e)))
```

The engine already rolled back the whole batch - there is nothing to undo.

### Transient failure - retry with an idempotency key

`QueryException` covers transport and 5xx failures. With an idempotency key,
retrying a transaction is safe (see [transactions.md](transactions.md)).

```clojure
(defn run [db build-txn key]
  ;; build-txn is a fn that returns a fresh transaction with the same ops.
  (try
    (txn/commit (build-txn db) key)
    (catch dev.visorcraft.mongreldb.AuthException e throw e)     ;; not transient
    (catch dev.visorcraft.mongreldb.ConflictException e throw e) ;; not transient
    (catch dev.visorcraft.mongreldb.MongrelDBException e
      ;; QueryException / network - caller may retry with the same key
      (throw e))))
```

### Transaction-state error

Calling `commit` or `rollback` twice on the same transaction throws
`IllegalStateException`. That is a programming bug - fix the control flow
rather than catching it.

## Quick reference

```clojure
;; Category checks (most specific first):
(catch dev.visorcraft.mongreldb.AuthException e ...)      ;; 401/403
(catch dev.visorcraft.mongreldb.NotFoundException e ...)  ;; 404
(catch dev.visorcraft.mongreldb.ConflictException e ...)  ;; 409
(catch dev.visorcraft.mongreldb.QueryException e ...)     ;; 400/5xx/network
(catch dev.visorcraft.mongreldb.MongrelDBException e ...) ;; base

;; Detail extraction on a conflict:
(catch dev.visorcraft.mongreldb.ConflictException e
  (.code e)    ;; String, e.g. "UNIQUE_VIOLATION", or nil
  (.opIndex e) ;; Integer or nil
  (.status e)  ;; int, e.g. 409
  (.getMessage e)) ;; String
```

## Next steps

- [transactions.md](transactions.md) - constraint handling and retries in context
- [auth.md](auth.md) - credential management
