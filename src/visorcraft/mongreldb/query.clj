(ns visorcraft.mongreldb.query
  "Fluent builder for the daemon's `/kit/query` endpoint.

  Conditions push down to the engine's specialized indexes for sub-millisecond
  lookups. Condition parameters accept friendly aliases that are translated to
  the server's exact on-wire keys before sending (see `normalize-condition`):

    column        -> column_id
    min / max     -> lo / hi
    min_inclusive -> lo_inclusive
    max_inclusive -> hi_inclusive

  The server's canonical keys are accepted directly too.

  Usage:

      (require '[visorcraft.mongreldb.core :as mdb])
      (let [b (-> (mdb/query db \"orders\")
                  (where \"range\" {:column 3 :min 100.0 :max 150.0})
                  (projection [1 2])
                  (limit 100))]
        (println (execute b))     ;; rows
        (println (truncated b)))  ;; was the result capped?"
  (:require [visorcraft.mongreldb.core :as core]
            [visorcraft.mongreldb.json :as json])
  (:import [java.nio.charset StandardCharsets]))

(defn- normalize-key
  "Translate a single friendly alias to the server's canonical key, honoring the
  type-specific value->pattern alias for full-text conditions."
  [cond-type k]
  (let [sk (cond (nil? k) nil (keyword? k) (name k) :else (str k))]
    (cond
      (= sk "column")        (if (keyword? k) :column_id "column_id")
      (= sk "min")           (if (keyword? k) :lo "lo")
      (= sk "max")           (if (keyword? k) :hi "hi")
      (= sk "min_inclusive") (if (keyword? k) :lo_inclusive "lo_inclusive")
      (= sk "max_inclusive") (if (keyword? k) :hi_inclusive "hi_inclusive")
      (= sk "value")
      (if (or (= cond-type "fm_contains") (= cond-type "fm_contains_all"))
        (if (keyword? k) :pattern "pattern")
        (if (keyword? k) :value "value"))
      :else k)))

(defn normalize-condition
  "Translate friendly parameter aliases to the server's canonical on-wire keys.
  Both spellings (keyword or string) are accepted, so callers may use whichever
  is clearer."
  [cond-type params]
  (into (if (map? params) (empty params) {})
        (for [[k v] params]
          [(normalize-key (str cond-type) k) v])))

(defn builder
  "Construct a query builder for `table` against `client`."
  [client table]
  {:client         client
   :table          table
   :conditions     []
   :projection     nil
   :limit          nil
   :last-truncated false})

(defn where
  "Add a native condition. Conditions are AND-ed together. `cond-type` is a
  string like \"pk\", \"range\", \"bitmap_eq\", \"fm_contains\", \"ann\", etc."
  [b cond-type params]
  (when (nil? cond-type) (throw (ex-info "cond-type is required" {})))
  (when (nil? params) (throw (ex-info "params is required" {})))
  (let [entry {(str cond-type) (normalize-condition (str cond-type) params)}]
    (update b :conditions conj entry)))

(defn projection
  "Set the column ids to return. nil (the default) means all columns."
  [b column-ids]
  (assoc b :projection (when column-ids (vec column-ids))))

(defn limit
  "Cap the number of rows returned."
  [b n]
  (assoc b :limit (long n)))

(defn build
  "Build the request payload that will be sent to /kit/query."
  [{:keys [table conditions projection limit]}]
  (cond-> {:table table}
    (seq conditions)   (assoc :conditions (vec conditions))
    (some? projection) (assoc :projection projection)
    (some? limit)      (assoc :limit limit)))

(defn execute
  "Run the query, returning a vector of row maps. Also records whether the result
  was truncated by `limit`; check it with `truncated`.

  Returns `[rows truncated]` so callers don't have to call `truncated` after."
  ([b] (first (execute-full b)))
  ([b _opts] (first (execute-full b))))

(defn execute-full
  "Run the query and return `[rows truncated]`."
  [b]
  (let [client (:client b)
        body (core/post client "/kit/query" (build b))
        parsed (if (or (nil? body)
                       (empty? (String. ^bytes body StandardCharsets/UTF_8)))
                 nil
                 (json/parse body))
        rows (if (and (map? parsed) (vector? (:rows parsed)))
               (vec (for [row (:rows parsed)]
                      (if (map? row) row {})))
               [])
        truncated (if (and (map? parsed) (boolean? (:truncated parsed)))
                    (:truncated parsed)
                    false)]
    [rows truncated]))

(defn truncated
  "Report whether the most recent `execute-full` result was capped by the query
  limit. Prefer using the second return value of `execute-full` directly."
  [b]
  (boolean (:last-truncated b)))
