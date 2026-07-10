# SQL

MongrelDB ships a DataFusion-backed SQL engine at `POST /sql`. From Clojure,
run SQL with `mdb/sql`:

```clojure
(def rows (mdb/sql db "SELECT 1"))
```

This guide covers the SQL surface - DDL, DML, `CREATE TABLE AS SELECT`,
recursive CTEs, and window functions - and when to reach for SQL versus the
native query builder.

---

## How `sql` behaves

`(mdb/sql db sql)` sends `{"sql": "...", "format": "json"}` to `/sql`. It
returns the decoded rows when the daemon replies with a JSON result set, and
an empty vector otherwise.

In practice:

- **DDL and DML** (`CREATE TABLE`, `INSERT`, `UPDATE`, `DELETE`) reply with a
  non-JSON status body. `sql` returns `[]` - success is the signal.
- **`SELECT`** in most daemon builds streams Arrow IPC bytes rather than JSON.
  `sql` therefore returns `[]` for SELECTs too. Use the native query builder
  for typed row retrieval in application code, and use `sql` for statements
  whose execution is the goal (DDL/DML/admin). To get the raw Arrow IPC bytes
  for a SELECT, use `mdb/sql-arrow`.

Errors are mapped to the same typed exceptions as everything else: an HTTP 400
or 5xx raises `QueryException`; 409 raises `ConflictException`; and so on.
See [errors.md](errors.md).

```clojure
(try
  (mdb/sql db "INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)")
  (catch dev.visorcraft.mongreldb.ConflictException e
    (when (= (.code e) "UNIQUE_VIOLATION")
      (println "duplicate row:" (.getMessage e)))))
```

## CREATE TABLE

Define a table in SQL instead of via `mdb/create-table`. Column ids are
assigned by the server when not stated.

```clojure
(mdb/sql db "
  CREATE TABLE products (
    id          INT64 PRIMARY KEY,
    name        VARCHAR,
    price       FLOAT64,
    category    VARCHAR,
    in_stock    BOOLEAN
  )")
```

## INSERT

```clojure
(mdb/sql db "INSERT INTO products (id, name, price, category, in_stock) VALUES (1, 'Widget', 9.99, 'tools', true)")
(mdb/sql db "INSERT INTO products VALUES (2, 'Gadget', 19.99, 'tools', true)")
```

For bulk inserts, the native batch transaction (`mdb/begin`) is usually faster
because it stages ops in one round trip without re-parsing SQL.

## UPDATE

```clojure
(mdb/sql db "UPDATE products SET price = 14.99 WHERE id = 1")
(mdb/sql db "UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'")
```

## DELETE

```clojure
(mdb/sql db "DELETE FROM products WHERE in_stock = false")
(mdb/sql db "DELETE FROM products WHERE id = 2")
```

## SELECT

```clojure
(mdb/sql db "SELECT id, name FROM products WHERE category = 'tools' ORDER BY price")
(mdb/sql db "SELECT category, COUNT(*) AS n FROM products GROUP BY category")
```

Remember SELECT bodies usually arrive as Arrow IPC, so `sql` returns an empty
vector. To read rows back into Clojure maps, mirror the same lookup with the
query builder, or use `sql-arrow` to fetch the raw IPC bytes.

## CREATE TABLE AS SELECT

Materialize a query result into a new table. Great for snapshots, rollups,
and denormalized aggregates.

```clojure
;; Snapshot all high-value orders into a new table.
(mdb/sql db "CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500")

;; Roll up sales by customer.
(mdb/sql db "
  CREATE TABLE sales_by_customer AS
  SELECT customer, SUM(amount) AS total
  FROM orders
  GROUP BY customer")
```

The new table inherits column types from the query. Query it afterward with
the native builder or SQL.

## Recursive CTEs

`WITH RECURSIVE` is fully supported. Classic use cases: series generation,
hierarchy/graph traversal.

```clojure
;; Generate the numbers 1..10.
(mdb/sql db "
  WITH RECURSIVE r(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM r WHERE n < 10
  )
  SELECT n FROM r")
```

A common practical example is walking an adjacency list:

```clojure
(mdb/sql db "
  WITH RECURSIVE descendants(id) AS (
    SELECT id FROM categories WHERE id = 1
    UNION ALL
    SELECT c.id FROM categories c
    JOIN descendants d ON c.parent_id = d.id
  )
  SELECT id FROM descendants")
```

## Window functions

Window functions compute aggregates/rankings across a moving window without
collapsing rows. Useful for top-N-per-group, running totals, and row numbers.

```clojure
;; Row number within each customer, ordered by amount descending.
(mdb/sql db "
  SELECT id, customer, amount,
         ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) AS rn
  FROM orders")

;; Running total per customer.
(mdb/sql db "
  SELECT id, customer, amount,
         SUM(amount) OVER (PARTITION BY customer ORDER BY id) AS running_total
  FROM orders")
```

`RANK()`, `DENSE_RANK()`, `LAG()`, `LEAD()`, `NTILE()`, and the usual
window-frame clauses are available through DataFusion.

## When to use SQL vs. the query builder

Both read from the same tables, but they are optimized for different jobs.

| Reach for | When |
|-----------|------|
| **Query builder** | Point lookups, range scans, bitmap filters, full-text, and vector similarity that map to a native index. Sub-millisecond, no parser overhead, and rows decode into Clojure maps directly. |
| **SQL** | DDL (`CREATE TABLE`, schemas, materialized views), multi-statement setup, joins, recursive CTEs, window functions, and arbitrary aggregates. Also the natural choice for admin scripts and one-off analysis. |

Rules of thumb:

- Need a typed vector of matching rows? Use the query builder.
- Building/dropping tables, or running a `CREATE TABLE AS SELECT`? Use SQL.
- Joining multiple tables, computing rankings, or walking a graph? Use SQL.
- Filtering by one or more indexed columns? Use the query builder - it is
  faster and avoids Arrow-to-Clojure decoding.

Mix freely: create tables with SQL, write rows with `mdb/put`, read them
back with the query builder, and run analytics with SQL.

## Next steps

- [queries.md](queries.md) - every native index condition in detail
- [transactions.md](transactions.md) - bulk inserts via batch transactions
- [errors.md](errors.md) - handling SQL execution errors
