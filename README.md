<p align="center">
  <img src="assets/mongrel.png" alt="MongrelDB logo" width="250" />
</p>

<h1 align="center">MongrelDB Clojure Client</h1>

<p align="center">
  <b>Pure Clojure client for MongrelDB - embedded+server database with SQL, vector search, full-text search, and AI-native retrieval.</b>
  <br />
  No external dependencies at runtime - built on the standard-library <code>java.net.http.HttpClient</code>. The API mirrors the MongrelDB Java, Scala, Ruby, and Go clients.
</p>

<p align="center">
  <a href="https://clojure.org/"><img src="https://img.shields.io/badge/Clojure-1.11-5881d8.svg" alt="Clojure" /></a>
  <a href="https://github.com/visorcraft/MongrelDB-Clojure/actions/workflows/ci.yml"><img src="https://github.com/visorcraft/MongrelDB-Clojure/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License" /></a>
</p>

## Package

| Surface | Coordinates | How to depend |
|---|---|---|
| Clojure client | `visorcraft/mongreldb` (git dep) | `org.slf4j/slf4j-nop` not required |

```clojure
;; deps.edn
{visoncraft/mongreldb
 {:git/url "https://github.com/visorcraft/MongrelDB-Clojure.git"
  :sha     "..."}}
```

History retention: `history-retention` and `set-history-retention-epochs`.

## Requirements

- **Clojure 1.11 or newer** (any JVM Clojure)
- **JDK 11 or newer** (uses `java.net.http.HttpClient`)
- A running [`mongreldb-server`](https://github.com/visorcraft/MongrelDB) daemon

## What It Provides

- **Typed CRUD** over the Kit transaction endpoint: `put`, `upsert` (insert-or-update on PK conflict), `delete` by row id or primary key, all with optional idempotency keys for safe retries.
- **Fluent query builder** that pushes conditions down to the engine's specialized indexes for sub-millisecond lookups: bitmap equality/IN, learned-range, null checks, FM-index full-text search, HNSW vector similarity (`ann`), and sparse vector match. Friendly aliases (`:column` -> `:column_id`, `:min`/`:max` -> `:lo`/`:hi`) are translated to the server's on-wire keys.
- **Idempotent batch transactions** - operations staged locally and committed atomically, with the engine enforcing unique, foreign-key, and check constraints at commit time. Idempotency keys return the original response on duplicate commits, even after a crash.
- **Full SQL access** through the DataFusion-backed `/sql` endpoint: recursive CTEs, window functions, `CREATE TABLE AS SELECT`, materialized views, and multi-statement execution.
- **Schema management**: typed table creation with enum/default fields and native constraints, full schema catalog, and per-table descriptors.
- **User/role/credentials management** via SQL: Argon2id-hashed catalog users, roles, and `GRANT`/`REVOKE` table-level permissions, all executed through `sql`.
- **Maintenance**: compaction (all tables or per-table).
- **Auth**: Bearer token (`--auth-token` mode) and HTTP Basic (`--auth-users` mode), with the bearer token taking precedence.
- **Typed exception hierarchy**: `MongrelDBException` (base), `AuthException` (401/403), `NotFoundException` (404), `ConflictException` (409, with code + op-index), and `QueryException` (everything else, including network failures).
- **Robust JSON handling**: a self-contained JSON codec (no external deps); the `/sql` endpoint's Arrow IPC bodies are tolerated gracefully.

## Install

Add the git dependency above to your `deps.edn`, or check out this repo and start a REPL with `clojure -M:examples -m basic-crud`.

## Examples

Task-focused, commented guides live in [`docs/`](docs):

- [Quickstart](docs/quickstart.md) - install, start the daemon, write and run a complete program.
- [Transactions](docs/transactions.md) - batch commits, idempotency keys, constraint handling.
- [Queries](docs/queries.md) - every native condition type and the index it pushes down to.
- [SQL](docs/sql.md) - recursive CTEs, window functions, advanced SQL.
- [Authentication](docs/auth.md) - Bearer token, HTTP Basic, and open modes.
- [Errors](docs/errors.md) - the exception hierarchy and recovery patterns.

## Quick Example

```clojure
(require '[visorcraft.mongreldb.core :as mdb]
         '[visorcraft.mongreldb.query :as q])

;; Connect to a running mongreldb-server daemon.
(def db (mdb/connect "http://127.0.0.1:8453"))

;; Create a table. Column ids are stable on-wire identifiers.
(mdb/create-table db "orders"
                  [{"id" 1 "name" "id"       "ty" "int64"   "primary_key" true  "nullable" false}
                   {"id" 2 "name" "customer" "ty" "varchar" "primary_key" false "nullable" false}
                   {"id" 3 "name" "amount"   "ty" "float64" "primary_key" false "nullable" false}])

;; Insert rows (cells map column id -> value).
(mdb/put db "orders" {1 1, 2 "Alice", 3 99.50})
(mdb/put db "orders" {1 2, 2 "Bob",   3 150.00})

;; Upsert (insert or update on PK conflict).
(mdb/upsert db "orders" {1 1, 2 "Alice", 3 120.00} {3 120.00})

;; Query with a native index condition (range on i64 amount).
(let [[rows] (-> (mdb/query db "orders")
                 (q/where "range" {:column 1 :min 1 :max 100})
                 (q/projection [1 2])
                 (q/limit 100)
                 (q/execute-full))]
  (println rows))

(println (mdb/count* db "orders")) ;; 2

;; Run SQL.
(mdb/sql db "UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
```

Column maps pass `enum_variants`, scalar `default_value`, and dynamic
`default_expr` (`"now"` or `"uuid"`) unchanged. The four-arity
form also sends the daemon's complete table `constraints` object:

```clojure
(mdb/create-table db "scores" columns
  {:checks [{:id 1 :name "score_nonneg"
             :expr {:Ge [{:Col 3} {:Lit {:Float64 0.0}}]}}]})
```

## Authentication

```clojure
;; Bearer token (--auth-token mode)
(mdb/connect {:url "http://127.0.0.1:8453" :token "my-secret-token"})

;; HTTP Basic (--auth-users mode)
(mdb/connect {:url "http://127.0.0.1:8453" :username "admin" :password "s3cret"})

;; No args -> default daemon address 127.0.0.1:8453.
(mdb/connect)
```

## Batch transactions

Operations are staged locally and committed atomically. The engine enforces
unique, foreign-key, and check constraints at commit time.

```clojure
(require '[visorcraft.mongreldb.transaction :as txn])

(let [t (-> (mdb/begin db)
            (txn/put "orders" {1 10, 2 "Dave", 3 50.00})
            (txn/put "orders" {1 11, 2 "Eve",  3 75.00})
            (txn/delete-by-pk "orders" 2))]
  (println "Staged" (txn/count* t) "operations")
  (try
    (let [{:keys [results]} (txn/commit t)]   ;; atomic - all or nothing
      (println "Committed" (count results) "ops"))
    (catch dev.visorcraft.mongreldb.ConflictException e
      (println "Constraint violated:" (.code e) "-" (.getMessage e)))))

;; Idempotent commit - safe to retry; the daemon returns the original response.
(-> (mdb/begin db)
    (txn/put "orders" {1 20, 2 "Frank", 3 100.00})
    (txn/commit "order-20-create"))
```

## Native query builder

Conditions push down to the engine's specialized indexes. The builder accepts
friendly aliases that are translated to the server's on-wire keys: `:column`
(-> `:column_id`), `:min`/`:max` (-> `:lo`/`:hi`). The canonical keys are also
accepted directly.

```clojure
;; Bitmap equality (low-cardinality columns).
(q/execute (-> (mdb/query db "orders")
               (q/where "bitmap_eq" {:column 2 :value "Alice"})))

;; Range query on a float64 column (learned-range index). Use "range_f64" for
;; float64 columns and "range" for i64 columns.
(q/execute (-> (mdb/query db "orders")
               (q/where "range_f64" {:column 3 :min 50.0 :max 150.0
                                      :max_inclusive false})
               (q/limit 100)))

;; Full-text search (FM-index).
(q/execute (-> (mdb/query db "documents")
               (q/where "fm_contains" {:column 2 :value "database performance"})
               (q/limit 10)))

;; Vector similarity search (HNSW).
(q/execute (-> (mdb/query db "embeddings")
               (q/where "ann" {:column 2 :query [0.1 0.2 0.3] :k 10})))

;; Check whether a result was capped by the limit.
(let [[rows truncated] (-> (mdb/query db "orders")
                           (q/where "range_f64" {:column 3 :min 0})
                           (q/limit 100)
                           (q/execute-full))]
  (when truncated
    ;; result set hit the limit; more matches exist on the server
    ))
```

## SQL

```clojure
(mdb/sql db "INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
(mdb/sql db "CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")

;; Recursive CTEs and window functions.
(mdb/sql db "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<10) SELECT n FROM r")
(mdb/sql db "SELECT id, ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) FROM orders")
```

## User & role management

User, role, and permission management is performed through SQL against the
daemon's catalog. Passwords are Argon2id-hashed server-side.

```clojure
(mdb/sql db "CREATE USER admin WITH PASSWORD 's3cret-pw'")
(mdb/sql db "ALTER USER admin SET ADMIN TRUE")

(mdb/sql db "CREATE ROLE analyst")
(mdb/sql db "GRANT select ON orders TO analyst")  ;; table-level permission
(mdb/sql db "GRANT analyst TO alice")

(mdb/sql db "SELECT username FROM catalog.users") ;; list users
(mdb/sql db "SELECT name FROM catalog.roles")      ;; list roles
```

## Error handling

Every non-2xx response is mapped to a typed exception. Catch the specific class
for the category, or `MongrelDBException` for any client failure.

```clojure
(try
  (mdb/put db "orders" {1 1})  ;; duplicate PK (with a UNIQUE constraint)
  (catch ConflictException e
    (println "Constraint:" (.code e))           ;; UNIQUE_VIOLATION
    (println "Op index:"   (.opIndex e)))        ;; offending op in the batch
  (catch AuthException e
    (println "Not authorized:" (.getMessage e)))
  (catch NotFoundException e
    (println "Not found:" (.getMessage e)))
  (catch QueryException e
    (println "Query/server error:" (.getMessage e)))
  (catch MongrelDBException e
    (println "Error:" (.getMessage e))))
```

## API reference

### `visorcraft.mongreldb.core`

| Function | Description |
|----------|-------------|
| `connect`, `(connect opts)` | Construct a client (`:url` defaults to `http://127.0.0.1:8453`); also accepts `:token`, `:username`, `:password` |
| `health` -> boolean | Check daemon health |
| `table-names` -> vector | List table names |
| `create-table name columns` / `create-table name columns constraints` -> int | Create a table; the four-arity form forwards the native constraints object |
| `drop-table name` -> nil | Drop a table |
| `count* table` -> int | Row count |
| `put table cells`, `(put ... idem)` -> map | Insert a row |
| `upsert table cells`, `(upsert ... update-cells)`, `(upsert ... idem)` -> map | Upsert a row |
| `delete table row-id` -> nil | Delete by row id |
| `delete-by-pk table pk` -> nil | Delete by primary key |
| `query client table` -> builder | Start a native query (see `visorcraft.mongreldb.query`) |
| `sql sql` -> vector | Execute SQL (JSON mode) |
| `sql-arrow sql` -> bytes | Execute SQL requesting raw Arrow IPC bytes |
| `schema` -> map | Full schema catalog |
| `schema-for table` -> map | Single-table descriptor |
| `compact` -> map | Compact all tables |
| `compact-table name` -> map | Compact one table |
| `begin` -> transaction | Start a batch (see `visorcraft.mongreldb.transaction`) |

### `visorcraft.mongreldb.query`

| Function | Description |
|----------|-------------|
| `where b cond-type params` -> b | Add a native condition (AND-ed) |
| `projection b column-ids` -> b | Set column projection |
| `limit b n` -> b | Set row limit |
| `build b` -> map | Build the request payload |
| `execute b` -> rows | Run the query |
| `execute-full b` -> `[rows truncated]` | Run the query and report truncation |
| `normalize-condition cond-type params` -> map | Friendly-alias translation |

### `visorcraft.mongreldb.transaction`

| Function | Description |
|----------|-------------|
| `put txn table cells`, `(put ... returning)` -> txn | Stage an insert |
| `upsert txn table cells`, `(upsert ... update-cells)`, `(upsert ... returning)` -> txn | Stage an upsert |
| `delete txn table row-id` -> txn | Stage a delete by row id |
| `delete-by-pk txn table pk` -> txn | Stage a delete by primary key |
| `count* txn` -> int | Number of staged operations |
| `commit txn`, `(commit idem)` -> `{:txn :results}` | Commit atomically |
| `rollback txn` -> txn | Discard all operations |

### Exceptions

| Class | HTTP status | Notes |
|-------|-------------|-------|
| `dev.visorcraft.mongreldb.MongrelDBException` | - | Base class for all client errors |
| `dev.visorcraft.mongreldb.AuthException` | 401, 403 | Bad or missing credentials |
| `dev.visorcraft.mongreldb.NotFoundException` | 404 | Missing table, schema, or resource |
| `dev.visorcraft.mongreldb.ConflictException` | 409 | Constraint violation; carries `code` and `opIndex` |
| `dev.visorcraft.mongreldb.QueryException` | 400, 5xx, network | Everything else |

## Building and testing

The test suite uses `clojure.test`. It is split into two layers:

- **Offline unit tests** - JSON codec, query-builder alias translation, cells
  flattening, and exception-shape checks. No daemon needed.
- **Live integration tests** - boots a real `mongreldb-server` daemon and
  exercises the full client surface (14-operation conformance matrix). Skips
  automatically when no binary is available.

```sh
# Compile the five Java exception classes AOT (one-time).
mkdir -p target/classes
javac -d target/classes $(find java-src -name '*.java')

# Offline unit tests only.
clojure -M:test -m visorcraft.mongreldb.live-test --offline

# Full suite (boots a daemon when one is available).
clojure -M:test -m visorcraft.mongreldb.live-test
```

Fetch a prebuilt server binary from the [MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases)
and place it at `./bin/mongreldb-server`, set `MONGRELDB_SERVER`, or install it
on `PATH`:

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.48.0/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

The live harness resolves the binary in this order: the `MONGRELDB_SERVER` env
var, `./bin/mongreldb-server`, `mongreldb-server` on `PATH`. Or point it at an
already-running daemon with `MONGRELDB_URL`.

## Contributing

Contributions are welcome. Please:

1. Open an issue first for non-trivial changes.
2. Add focused tests near your change - the suite must stay green.
3. Run `clojure -M:test -m visorcraft.mongreldb.live-test` before submitting.
4. Keep the client dependency-free (standard library only at runtime).

## License

Dual-licensed under the **MIT License** or the **Apache License, Version 2.0**,
at your option. See [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE) for the full text.

`SPDX-License-Identifier: MIT OR Apache-2.0`
