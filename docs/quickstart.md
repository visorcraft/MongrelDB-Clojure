# Quickstart

Zero to a running MongrelDB Clojure program in fifteen minutes. This guide
assumes a fresh machine and walks through installing the prerequisites,
starting the daemon, and writing, running, and understanding a complete
program.

---

## 1. Prerequisites

You need two things installed: the Clojure toolchain and a `mongreldb-server`
daemon.

### Install Clojure 1.11 and JDK 11 or newer

The client needs Clojure 1.11+ and JDK 11+ (for `java.net.http.HttpClient`).
Install Clojure via the [Clojure CLI](https://clojure.org/guides/install_clojure),
and a JDK from your package manager (`pacman -S jdk-openjdk`,
`brew install openjdk@11`). Verify:

```sh
java -version
clojure --version
```

### Install mongreldb-server

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.50.0/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

Verify it runs:

```sh
./bin/mongreldb-server --version
```

## 2. Start the daemon

By default `mongreldb-server` listens on `http://127.0.0.1:8453` and stores
data in the current working directory.

```sh
mkdir -p /tmp/mdb-data && cd /tmp/mdb-data
/path/to/mongreldb-server
```

In another terminal, sanity-check it:

```sh
curl http://127.0.0.1:8453/health
# ok
```

Leave the daemon running for the rest of this guide.

## 3. Create a project and pull in the client

Add the client to your `deps.edn`:

```clojure
{:deps {visorcraft/mongreldb
        {:git/url "https://github.com/visorcraft/MongrelDB-Clojure.git"
         :sha     "..."}}}
```

Or check out this repo and run the examples directly:

```sh
git clone https://github.com/visorcraft/MongrelDB-Clojure.git
cd MongrelDB-Clojure
mkdir -p target/classes
javac -d target/classes $(find java-src -name '*.java')
```

## 4. Write your first program

Create `src/demo.clj`:

```clojure
(ns demo
  (:require [visorcraft.mongreldb.core :as mdb]
            [visorcraft.mongreldb.query :as q]))

;; 1. Connect to the daemon. No args falls back to http://127.0.0.1:8453.
(def db (mdb/connect "http://127.0.0.1:8453"))

;; 2. Health check before doing anything else.
(when-not (mdb/health db)
  (binding [*out* *err*] (println "daemon not reachable"))
  (System/exit 1))

;; 3. Create a table. Each column has a stable numeric id, a name, a type, and
;;    flags. The first column is the primary key.
(let [tid (mdb/create-table db "orders"
                            [{"id" 1 "name" "id"       "ty" "int64"   "primary_key" true  "nullable" false}
                             {"id" 2 "name" "customer" "ty" "varchar" "primary_key" false "nullable" false}
                             {"id" 3 "name" "amount"   "ty" "float64" "primary_key" false "nullable" false}])]
  (println "created table id:" tid))

;; Column maps may include `enum_variants` and `default_value`. Pass a fourth
;; argument to forward the native table `constraints` object (including CHECKs).

;; 4. Insert rows. Cells maps column id -> value.
(mdb/put db "orders" {1 1, 2 "Alice", 3 99.5})
(mdb/put db "orders" {1 2, 2 "Bob",   3 150.0})

;; 5. Query with a native index condition. Projection selects only column ids
;;    1 and 2. Use "range_f64" for float64 columns, "range" for i64 columns.
(let [[rows] (-> (mdb/query db "orders")
                 (q/where "range_f64" {:column 3 :min 100.0})
                 (q/projection [1 2])
                 (q/limit 100)
                 (q/execute-full))]
  (doseq [row rows] (println "row:" row)))

;; 6. Count the rows.
(println "total rows:" (mdb/count* db "orders"))
```

Run it:

```sh
clojure -M -m demo
```

You should see something like:

```
created table id: 1
row: {:cells [2 Bob]}
total rows: 2
```

## 5. What each part does

| Code | What it does |
|------|--------------|
| `(mdb/connect url)` | Builds an HTTP client targeting one daemon. |
| `(mdb/health db)` | GET `/health`; returns `true` when the daemon answers. |
| `(mdb/create-table db name cols)` / `(mdb/create-table db name cols constraints)` | POST `/kit/create_table`. Column `id`s are the on-wire identifiers; optional column `enum_variants`/`default_value` keys and the native `constraints` object are forwarded unchanged. |
| `(mdb/put db table cells)` | Single-op transaction: POST `/kit/txn` with one `put` op. `cells` is flattened to `[col_id, val, ...]`. |
| `(mdb/query db table)` | Starts a `/kit/query` builder. |
| `(q/where ...)` | Pushes a condition down to a native index. |
| `(q/projection ...)` | Server returns only those column ids, saving bandwidth. |
| `(q/limit ...)` | Caps the result; use `execute-full` to detect truncation. |
| `(q/execute-full ...)` | Sends the query and returns `[rows truncated]`. |
| `(mdb/count* db table)` | GET `/tables/{name}/count`. |

## 6. Constrained columns

`create-table` forwards column maps to the daemon verbatim, so the recognized
keys (`enum_variants`, `default_value`, `default_expr`) are passed unchanged.

| Key | Effect |
|-----|--------|
| `enum_variants` | Restrict the column to one of the listed string values. |
| `default_value` | String/number/boolean/null default applied when the cell is omitted on a `put`. Literal values `"now"` and `"uuid"` are sent as static strings; use `default_expr` for dynamic `now`/`uuid` defaults. |
| `default_expr` | Dynamic `"now"` or `"uuid"`. Takes precedence over `default_value`. |

```clojure
(mdb/create-table db "orders"
                  [{"id" 1 "name" "id" "ty" "int64" "primary_key" true}
                   {"id" 2 "name" "status" "ty" "varchar"
                    "enum_variants" ["pending" "shipped"]
                    "default_value" "pending"}
                   {"id" 3 "name" "retries" "ty" "int64"
                    "default_value" 3}
                   {"id" 4 "name" "tag" "ty" "varchar"
                    "default_value" "now"}        ; static string literal
                   {"id" 5 "name" "created_at" "ty" "timestamp"
                    "default_expr" "now"}])       ; dynamic default
```

## 7. History retention and time travel

MongrelDB keeps a durable MVCC history window. The size of the window is measured
in epochs and controls how far back `AS OF EPOCH` queries can read. The client
exposes three pieces of the API:

- `(mdb/history-retention db)` returns the current window and earliest retained
  epoch.
- `(mdb/set-history-retention-epochs db epochs)` changes the durable window.
  Increasing retention cannot bring back epochs that have already been
  garbage-collected.
- `(mdb/last-epoch db)` returns the commit epoch of the most recent successful
  `/kit/txn` call, or `0` before any such call. It is a convenient pinning point
  for time-travel reads.

```clojure
;; Widen the history window.
(def updated (mdb/set-history-retention-epochs db 10000))
(:history_retention_epochs updated)
(:earliest_retained_epoch updated)

;; Pin a read to the epoch of the last committed write.
(mdb/put db "orders" {1 1})
(mdb/sql db (str "SELECT id FROM orders AS OF EPOCH " (mdb/last-epoch db)))
```

## 8. Common pitfalls

**Using the column name instead of the column id.** Every on-wire API uses
the numeric `id` from `create-table`, never the `name`. The query builder's
`:column` alias maps to the server's `column_id` - pass the integer id, not
the string name:

```clojure
;; Wrong:
(q/where "range" {:column "amount" :min 100.0})
;; Right:
(q/where "range" {:column 3 :min 100.0})
```

**Treating a single `put` as non-transactional.** `put` is a one-op
transaction. A unique constraint violation surfaces as a
`ConflictException` (HTTP 409), not as a silent no-op.

**Calling `commit` twice on the same transaction.** The second call throws
`IllegalStateException`. Create a fresh `(mdb/begin db)` for each logical
unit of work.

**Expecting `sql` to always return rows.** The `/sql` endpoint streams Arrow
IPC for `SELECT` in most builds, so `sql` returns an empty vector (not an
error) for result sets. Use it for DDL/DML and statements whose success is
the signal; use the native query builder for typed row retrieval.

**Pointing at a daemon that requires auth.** If the daemon was started with
`--auth-token` or `--auth-users`, every call throws `AuthException` unless
you pass `:token` or `:username`/`:password` to `connect`. See
[auth.md](auth.md).

## Next steps

- [transactions.md](transactions.md) - atomic batches, idempotency, retries
- [queries.md](queries.md) - every native index condition
- [sql.md](sql.md) - recursive CTEs, window functions, `CREATE TABLE AS SELECT`
- [auth.md](auth.md) - bearer tokens, basic auth, user/role management
- [errors.md](errors.md) - the full error hierarchy and recovery patterns
