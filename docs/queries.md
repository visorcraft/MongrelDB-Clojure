# Queries

The fluent query builder pushes conditions down to MongrelDB's native indexes
for sub-millisecond lookups - bitmap, learned-range, FM-index full text, HNSW
vector similarity, and more. Each condition type maps to one specialized
index; conditions are AND-ed together.

```clojure
(let [[rows] (-> (mdb/query db "orders")
                 (q/where "range_f64" {:column 3 :min 100.0 :max 500.0})
                 (q/projection [1 2])
                 (q/limit 100)
                 (q/execute-full))])
```

This guide covers every condition type, projection, limits and truncation,
combining conditions, and the friendly aliases the builder translates for you.

---

## The basics

Every query starts with `(mdb/query db table)` and ends with `execute` or
`execute-full`:

| Function | Purpose |
|----------|---------|
| `where b type params` | Add a native condition. Multiple `where` calls are AND-ed. |
| `projection b column-ids` | Return only these column ids (`nil` means all columns). |
| `limit b n` | Cap the number of rows. |
| `build b` | Produce the request payload (useful for debugging). |
| `execute b` | Send and decode the rows. |
| `execute-full b` | Send and return `[rows truncated]`. |

The request body produced by `build` matches the daemon's `/kit/query` shape:

```json
{
  "table": "orders",
  "conditions": [{"range_f64": {"column_id": 3, "lo": 100.0, "hi": 500.0, "lo_inclusive": true, "hi_inclusive": true}}],
  "projection": [1, 2],
  "limit": 100
}
```

## Condition types

`params` is a map. Column references use the numeric **column id**, never the
column name.

### `pk` - exact primary-key match

The fastest lookup. `:value` is the primary-key value.

```clojure
(q/execute (-> (mdb/query db "orders")
               (q/where "pk" {:value 42})))
```

### `range` - integer range (learned-range index)

Inclusive bounds. Omit `:lo` or `:hi` for an open range.

```clojure
(q/execute (-> (mdb/query db "orders")
               (q/where "range" {:column 3 :min 100 :max 500})))

;; Open-ended: amount >= 100
(q/execute (-> (mdb/query db "orders")
               (q/where "range" {:column 3 :min 100})))
```

### `range_f64` - float range with inclusive/exclusive control

Adds `:lo_inclusive` / `:hi_inclusive` flags (default inclusive).

```clojure
(q/execute (-> (mdb/query db "orders")
               (q/where "range_f64"
                        {:column        3
                         :min           100.0
                         :max           500.0
                         :min_inclusive true
                         :max_inclusive false}))) ;; (100.0, 500.0]
```

### `bitmap_eq` - equality on a bitmap-indexed column

Best for low-cardinality columns (status, category, booleans).

```clojure
(q/execute (-> (mdb/query db "orders")
               (q/where "bitmap_eq" {:column 2 :value "Alice"})))
```

### `bitmap_in` - IN predicate on a bitmap-indexed column

Match any of a set of values.

```clojure
(q/execute (-> (mdb/query db "orders")
               (q/where "bitmap_in" {:column 2 :values ["Alice" "Bob" "Carol"]})))
```

### `is_null` / `is_not_null` - null checks

```clojure
(q/execute (-> (mdb/query db "orders") (q/where "is_null"     {:column 3})))
(q/execute (-> (mdb/query db "orders") (q/where "is_not_null" {:column 3})))
```

### `fm_contains` - full-text substring search (FM-index)

Substring match within a column. Use `:pattern` (the server key) or the
friendly `:value` alias - both translate to `pattern` on the wire for FTS
conditions.

```clojure
(q/execute (-> (mdb/query db "documents")
               (q/where "fm_contains" {:column 2 :pattern "database performance"})
               (q/limit 10)))

;; Friendly alias: :value -> :pattern for fm_contains only.
(q/execute (-> (mdb/query db "documents")
               (q/where "fm_contains" {:column 2 :value "database"})))
```

### `fm_contains_all` - multiple substrings, all must match

```clojure
(q/execute (-> (mdb/query db "documents")
               (q/where "fm_contains_all" {:column 2 :patterns ["database" "performance"]})))
```

### `ann` - dense vector similarity (HNSW)

Approximate nearest-neighbors over a `float` vector column. `:k` is the
result count.

```clojure
(q/execute (-> (mdb/query db "embeddings")
               (q/where "ann" {:column 2 :query [0.1 0.2 0.3 0.4] :k 10})))
```

### `sparse_match` - sparse vector match

For sparse/bag-of-words vectors.

```clojure
(q/execute (-> (mdb/query db "docs")
               (q/where "sparse_match" {:column 2
                                        :query {"0" 1.0, "7" 0.5, "42" 2.0}
                                        :k 10})))
```

### `min_hash_similar` - MinHash similarity

Near-duplicate detection via MinHash signatures.

```clojure
(q/execute (-> (mdb/query db "pages")
               (q/where "min_hash_similar" {:column 2 :query [12 99 421 7] :k 5})))
```

## Projection (column selection)

`(q/projection b [1 2 ...])` restricts the columns in each returned row.
Pass `nil` (or skip the call) for all columns. Projecting to only the columns
you need cuts bandwidth and decode cost.

Returned rows carry their values in the `:cells` field, a flat
`[col_id, value, ...]` vector.

## Limit and the truncated flag

`(q/limit b n)` caps the result. When the server has more matches than the
limit allows, it returns the first `n` and sets `truncated: true`. Use
`execute-full` to read it directly:

```clojure
(let [[rows truncated] (-> (mdb/query db "orders")
                           (q/where "range" {:column 3 :min 0})
                           (q/limit 100)
                           (q/execute-full))]
  (when truncated
    ;; 100 rows came back but more exist on the server. Either raise the limit,
    ;; page with a range predicate on the PK, or accept the cap.
    (println "result capped at" (count rows) "; more rows available")))
```

## Multiple AND conditions

Chain `where` calls. Every condition must match; the server intersects the
index results.

```clojure
;; Customer is Alice AND amount is between 100 and 500.
(q/execute (-> (mdb/query db "orders")
               (q/where "bitmap_eq" {:column 2 :value "Alice"})
               (q/where "range" {:column 3 :min 100 :max 500})
               (q/projection [1 3])
               (q/limit 50)))
```

Because each `where` targets a different specialized index, the engine can
pick the most selective one to drive the lookup and intersect the rest.

## Friendly alias translation

The builder accepts readable parameter names and translates them to the
server's canonical on-wire keys. Both spellings work, so use whichever is
clearer in context.

| You write | Sent as | Applies to |
|-----------|---------|------------|
| `:column` | `:column_id` | all condition types |
| `:min` | `:lo` | `range`, `range_f64` |
| `:max` | `:hi` | `range`, `range_f64` |
| `:min_inclusive` | `:lo_inclusive` | `range_f64` |
| `:max_inclusive` | `:hi_inclusive` | `range_f64` |
| `:value` | `:pattern` | `fm_contains`, `fm_contains_all` only |

The `:value` -> `:pattern` alias applies **only** to FTS conditions, because
`pk` and `bitmap_eq` use `:value` as their canonical key. For those, write
`:value` directly.

```clojure
;; pk: :value stays :value (canonical)
(q/where b "pk" {:value 42})

;; fm_contains: :value is translated to :pattern
(q/where b "fm_contains" {:column 2 :value "search term"})
;; equivalent to:
(q/where b "fm_contains" {:column_id 2 :pattern "search term"})
```

## Putting it together

A realistic combined lookup - bitmap equality + range + projection + limit +
truncation check:

```clojure
(defn top-spenders [db customer]
  (let [[rows truncated] (-> (mdb/query db "orders")
                             (q/where "bitmap_eq" {:column 2 :value customer})
                             (q/where "range" {:column 3 :min 100})
                             (q/projection [1 3])
                             (q/limit 50)
                             (q/execute-full))]
    (when truncated
      (println "warning: top-spenders result capped at 50"))
    rows))
```

For arbitrary predicates, joins, and aggregations that the native indexes do
not cover, use SQL instead - see [sql.md](sql.md).
