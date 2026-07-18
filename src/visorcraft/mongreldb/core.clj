(ns visorcraft.mongreldb.core
  "The MongrelDB Clojure HTTP client.

  A pure-Clojure client for a running `mongreldb-server` daemon, built on the
  standard-library `java.net.http.HttpClient` (Java 11+). No external runtime
  dependencies. The API mirrors the MongrelDB Java, Scala, and Go clients.

  Connect with a base URL:

      (require '[visorcraft.mongreldb.core :as mdb])
      (def db (mdb/connect \"http://127.0.0.1:8453\"))
      (mdb/health db)   ;=> true"
  (:require [visorcraft.mongreldb.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$Builder HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util Base64]
           [java.nio.charset StandardCharsets]
           [dev.visorcraft.mongreldb MongrelDBException AuthException NotFoundException
            ConflictException QueryException]))

(def ^:const default-base-url "http://127.0.0.1:8453")

(def ^:const max-response-bytes 268435456)

;; ── Client construction ────────────────────────────────────────────────────

(defn- strip-trailing-slash [^String url]
  (let [base (if (or (nil? url) (empty? url)) default-base-url url)]
    (loop [b base]
      (if (.endsWith b "/") (recur (subs b 0 (dec (count b)))) b))))

(defn connect
  "Construct a MongrelDB client.

  May be called with no args (defaults to http://127.0.0.1:8453), a string
  base URL, or an options map:

    :url      daemon base URL (default http://127.0.0.1:8453)
    :token    bearer token (--auth-token mode)
    :username basic-auth username (--auth-users mode)
    :password basic-auth password"
  ([] (connect nil))
  ([opts]
   (let [opts (cond
                (nil? opts) {}
                (string? opts) {:url opts}
                :else opts)
         base-url (strip-trailing-slash (:url opts))
         http (-> (HttpClient/newBuilder)
                  (.connectTimeout (Duration/ofSeconds 30))
                  (.build))]
     {:base-url base-url
      :token (:token opts)
      :username (:username opts)
      :password (:password opts)
      :http http
      :last-epoch (atom 0)})))

;; ── Helpers ────────────────────────────────────────────────────────────────

(defn- reject-crlf [^String s label]
  (when (and s (>= (.indexOf s (int \return)) 0))
    (throw (QueryException. (str "mongreldb: illegal CR in " label))))
  (when (and s (>= (.indexOf s (int \newline)) 0))
    (throw (QueryException. (str "mongreldb: illegal LF in " label)))))

(defn ^:no-doc url-path-escape
  "Percent-encode a path segment so table names containing '/', '?', '#', or
  spaces cannot inject extra segments. Only RFC 3986 unreserved characters pass
  through; '/' is encoded as %2F."
  ^String [seg]
  (let [sb (StringBuilder.)
        unreserved (into #{} "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~")
        hex "0123456789ABCDEF"]
    (doseq [^char c (str seg)]
      (if (contains? unreserved c)
        (.append sb c)
        (doseq [b (.getBytes (str c) StandardCharsets/UTF_8)]
          (let [bb (bit-and b 0xff)]
            (.append sb "%")
            (.append sb (.charAt hex (bit-shift-right bb 4)))
            (.append sb (.charAt hex (bit-and bb 0x0f)))))))
    (.toString sb)))

(defn ^:no-doc flatten-cells
  "Flatten a column-id-to-value map to the server's [col_id, value, ...] vector
  in ascending column-id order. Stable ordering is required for idempotency
  keys: the server hashes the request payload, and unordered map iteration
  would make two commits of the same cells look like a reuse mismatch."
  [cells]
  (into []
        (mapcat (fn [[k v]] [k v]))
        (sort-by (fn [[k _]] k) cells)))

(defn- decode-txn-response
  "Pull the results array out of a /kit/txn response body and update the
  client's :last-epoch atom when the server reports a committed status."
  [client ^bytes body]
  (cond
    (nil? body) []
    (empty? (String. body StandardCharsets/UTF_8)) []
    :else (let [parsed (try (json/parse body) (catch Exception _ nil))]
            (when (and (map? parsed)
                       (= "committed" (:status parsed))
                       (integer? (:epoch parsed)))
              (reset! (:last-epoch client) (long (:epoch parsed))))
            (if (map? parsed)
              (if (vector? (:results parsed)) (:results parsed) [])
              (throw (QueryException. "mongreldb: decode txn response: unexpected JSON"))))))

(defn- first-result [results]
  (if (seq results) (first results) {}))

(defn- apply-auth [^HttpRequest$Builder req {:keys [token username password]}]
  (cond
    (and token (not (empty? token)))
    (do (reject-crlf token "token")
        (.header req "Authorization" (str "Bearer " token)))
    (and username (not (empty? username)))
    (do (reject-crlf username "username")
        (reject-crlf password "password")
        (let [creds (str username ":" (or password ""))
              encoded (.encodeToString (Base64/getEncoder)
                                       (.getBytes creds StandardCharsets/UTF_8))]
          (.header req "Authorization" (str "Basic " encoded))))))

(defn- decode-error-envelope
  "Decode the server's JSON error envelope and return [message code op-index].
  Falls back to the raw body when it is not JSON."
  [^bytes body]
  (cond
    (or (nil? body) (zero? (alength body))) [nil nil nil]
    :else
    (let [s (.trim (String. body StandardCharsets/UTF_8))]
      (if-not (.startsWith s "{")
        [s nil nil]
        (try
          (let [parsed (json/parse body)]
            (if-not (map? parsed) [s nil nil]
                    (let [err (:error parsed)]
                      (if (map? err)
                        [(:message err) (:code err) (:op_index err)]
                        [(:message parsed) (:code parsed) nil]))))
          (catch Exception _ [s nil nil]))))))

(defn- to-exception [^long status ^bytes body]
  (let [[raw-message code op-index] (decode-error-envelope body)
        message (cond
                  (and (or (nil? raw-message) (empty? raw-message))
                       (or (nil? body) (zero? (alength body))))
                  (case status
                    (401 403) (str "authentication failed (" status ")")
                    404 "resource not found"
                    409 "constraint violation"
                    (str "server error (" status ")"))
                  (or (nil? raw-message) (empty? raw-message))
                  (String. body StandardCharsets/UTF_8)
                  :else raw-message)]
    (cond
      (.startsWith message "not found:")
      (NotFoundException. message status code op-index)
      (or (= status 401) (= status 403))
      (AuthException. message status code op-index)
      (= status 404) (NotFoundException. message status code op-index)
      (= status 409) (ConflictException. message status code op-index)
      :else (QueryException. message status code op-index))))

(defn- strip-leading-slash [^String s]
  (loop [r s]
    (if (.startsWith r "/") (recur (subs r 1)) r)))

(defn- do-request
  "Build and run one request. The server's JSON extractors require an explicit
  Content-Type on requests carrying a JSON body, so one is added whenever the
  body is non-nil. Non-2xx responses are mapped to typed exceptions."
  [client method ^String path body]
  (reject-crlf path "request path")
  (let [^String base-url (:base-url client)
        ^HttpClient http (:http client)
        uri (URI/create (str base-url "/" (strip-leading-slash path)))
        payload-bytes (when (some? body) (json/to-bytes body))
        publisher (if payload-bytes
                    (HttpRequest$BodyPublishers/ofByteArray payload-bytes)
                    (HttpRequest$BodyPublishers/noBody))
        req-builder (-> (HttpRequest/newBuilder)
                        (.uri uri)
                        (.header "Accept" "application/json")
                        (.timeout (Duration/ofSeconds 30))
                        (.method method publisher))]
    (when payload-bytes (.header req-builder "Content-Type" "application/json"))
    (apply-auth req-builder client)
    (let [resp (try (.send http (.build req-builder)
                          (HttpResponse$BodyHandlers/ofByteArray))
                    (catch java.io.IOException e
                      (throw (QueryException.
                               (str "mongreldb: request " method " " path " failed: " (.getMessage e))
                               e)))
                    (catch InterruptedException e
                      (.interrupt (Thread/currentThread))
                      (throw (QueryException.
                               (str "mongreldb: request " method " " path " interrupted") e))))
          data (let [b (.body ^HttpResponse resp)] (if (nil? b) (byte-array 0) b))]
      (when (> (alength data) max-response-bytes)
        (throw (QueryException. (str "mongreldb: response body exceeds maximum size of "
                                     max-response-bytes " bytes"))))
      (let [status (.statusCode ^HttpResponse resp)]
        (if (and (>= status 200) (< status 300))
          data
          (throw (to-exception status data)))))))

(defn- http-get [client path] (do-request client "GET" path nil))
(defn- http-post [client path body] (do-request client "POST" path body))
(defn- http-put [client path body] (do-request client "PUT" path body))
(defn- http-delete [client path] (do-request client "DELETE" path nil))

(defn post
  "Public POST helper exposed for companion namespaces (query/transaction).
  Sends a JSON body and returns the raw byte-array response."
  {:no-doc true}
  [client path body] (http-post client path body))

(defn- post-decode
  "POST an empty body and decode the JSON object response."
  [client path]
  (let [body (http-post client path nil)]
    (if (or (nil? body) (empty? (String. body StandardCharsets/UTF_8)))
      {}
      (let [parsed (json/parse body)]
        (if (map? parsed) parsed {})))))

;; ── Public API: health & tables ────────────────────────────────────────────

(defn health
  "Return true if the daemon answered /health with a 2xx, false on any error."
  [client]
  (try (http-get client "/health") true
       (catch MongrelDBException _ false)))

(defn table-names
  "List all table names in the database."
  [client]
  (let [body (http-get client "/tables")]
    (if (empty? (String. body StandardCharsets/UTF_8))
      []
      (let [parsed (json/parse body)]
        (if (vector? parsed) (mapv #(if (nil? %) nil (str %)) parsed) [])))))

(defn history-retention
  "Return the current durable MVCC window and earliest retained epoch."
  [client]
  (json/parse (http-get client "/history/retention")))

(defn history-retention-epochs
  "Return the current durable MVCC window size in epochs."
  [client]
  (:history_retention_epochs (history-retention client)))

(defn earliest-retained-epoch
  "Return the oldest epoch still readable via `AS OF EPOCH` queries."
  [client]
  (:earliest_retained_epoch (history-retention client)))

(defn set-history-retention-epochs
  "Set the durable MVCC window size. Returns the updated window and earliest
  retained epoch."
  [client epochs]
  (json/parse (http-put client "/history/retention"
                        {:history_retention_epochs epochs})))

(defn last-epoch
  "Return the commit epoch of the most recent successful `/kit/txn` call, or 0
  before any such call."
  [client]
  @(:last-epoch client))

(defn- create-table-payload [name columns constraints]
  (cond-> {:name name :columns columns}
    (some? constraints) (assoc :constraints constraints)))

(defn create-table
  "Create a table with typed columns and optional constraints; return its id."
  ([client name columns]
   (create-table client name columns nil))
  ([client name columns constraints]
   (let [payload (create-table-payload name columns constraints)
         body (http-post client "/kit/create_table" payload)]
    (if (empty? (String. body StandardCharsets/UTF_8))
      0
      (let [parsed (json/parse body)]
        (if (map? parsed)
          (long (or (:table_id parsed) 0))
          0))))))

(defn drop-table
  "Drop a table by name."
  [client name]
  (http-delete client (str "/tables/" (url-path-escape name)))
  nil)

(defn count*
  "Return the row count for a table."
  [client table]
  (let [body (http-get client (str "/tables/" (url-path-escape table) "/count"))]
    (if (empty? (String. body StandardCharsets/UTF_8))
      (throw (QueryException. "mongreldb: malformed count response"))
      (let [parsed (json/parse body)]
        (if (and (map? parsed) (integer? (:count parsed)))
          (long (:count parsed))
          (throw (QueryException. "mongreldb: malformed count response")))))))

;; ── Public API: CRUD ───────────────────────────────────────────────────────

(defn- commit-one
  [client ops idempotency-key]
  (let [payload (cond-> {:ops ops}
                  (and idempotency-key (not (empty? idempotency-key)))
                  (assoc :idempotency_key idempotency-key))]
    (decode-txn-response client (http-post client "/kit/txn" payload))))

(defn put
  "Insert a row. `cells` is a column-id-to-value map, flattened to the server's
  [col_id, value, ...] array before sending."
  ([client table cells] (put client table cells nil))
  ([client table cells idempotency-key]
   (first-result (commit-one client [{:put {:table table :cells (flatten-cells cells)}}]
                             idempotency-key))))

(defn upsert
  "Insert a row, or update it on a primary-key conflict. `update-cells`, when
  non-nil, supplies the values written on conflict; nil means DO NOTHING."
  ([client table cells] (upsert client table cells nil nil))
  ([client table cells update-cells] (upsert client table cells update-cells nil))
  ([client table cells update-cells idempotency-key]
   (let [inner (cond-> {:table table :cells (flatten-cells cells)}
                 (some? update-cells) (assoc :update_cells (flatten-cells update-cells)))]
     (first-result (commit-one client [{:upsert inner}] idempotency-key)))))

(defn delete
  "Remove a row by its internal row id."
  [client table row-id]
  (commit-one client [{:delete {:table table :row_id row-id}}] nil)
  nil)

(defn delete-by-pk
  "Remove a row by its primary-key value."
  [client table pk]
  (commit-one client [{:delete_by_pk {:table table :pk pk}}] nil)
  nil)

;; ── Public API: query builder ──────────────────────────────────────────────

(defn query
  "Start a fluent query against `table`. Returns a map you can thread through
  `where`, `projection`, `limit`, and `execute` (see visorcraft.mongreldb.query)."
  [client table]
  ;; The query builder lives in its own namespace; expose a constructor here so
  ;; callers only need to require this namespace.
  ((requiring-resolve 'visorcraft.mongreldb.query/builder) client table))

;; ── Public API: SQL ────────────────────────────────────────────────────────

(defn sql
  "Execute a SQL statement via /sql requesting JSON output. Returns a vector of
  row maps. Empty vector for DDL/DML or Arrow responses."
  [client sql]
  (let [body (http-post client "/sql" {:sql sql :format "json"})
        s (.trim (String. body StandardCharsets/UTF_8))]
    (if (empty? s)
      []
      (try
        (let [parsed (json/parse body)]
          (if (vector? parsed)
            (mapv #(if (map? %)
                     (into {} (for [[k v] %] [(name k) v]))
                     {})
                  parsed)
            []))
        (catch Exception _ [])))))

(defn sql-arrow
  "Send a SQL statement requesting raw Arrow IPC bytes (format arrow)."
  [client sql]
  (http-post client "/sql" {:sql sql :format "arrow"}))

;; ── Public API: schema ─────────────────────────────────────────────────────

(defn schema
  "Return the full schema catalog (a map of table name -> descriptor)."
  [client]
  (let [body (http-get client "/kit/schema")]
    (if (empty? (String. body StandardCharsets/UTF_8))
      {}
      (let [parsed (json/parse body)]
        (if (map? parsed)
          (into {} (for [[k v] (or (:tables parsed) {})] [(name k) v]))
          {})))))

(defn schema-for
  "Return the descriptor for a single table."
  [client table]
  (let [body (http-get client (str "/kit/schema/" (url-path-escape table)))]
    (if (empty? (String. body StandardCharsets/UTF_8))
      {}
      (let [parsed (json/parse body)]
        (if (map? parsed)
          (into {} (for [[k v] parsed] [(name k) v]))
          {})))))

;; ── Public API: maintenance ────────────────────────────────────────────────

(defn compact "Compact (merge sorted runs) across all tables." [client] (post-decode client "/compact"))

(defn compact-table
  "Compact a single table."
  [client table]
  (post-decode client (str "/tables/" (url-path-escape table) "/compact")))

;; ── Public API: transactions ───────────────────────────────────────────────

(defn commit-txn
  "Send a batch of staged operations atomically. Exposed for the Transaction
  type; returns the per-operation results."
  {:no-doc true}
  [client ops idempotency-key]
  (if (empty? ops)
    []
    (let [payload (cond-> {:ops ops}
                    (and idempotency-key (not (empty? idempotency-key)))
                    (assoc :idempotency_key idempotency-key))]
      (decode-txn-response client (http-post client "/kit/txn" payload)))))

(defn begin
  "Begin a batch transaction. Operations staged on the returned transaction are
  committed atomically in a single /kit/txn request."
  [client]
  ((requiring-resolve 'visorcraft.mongreldb.transaction/begin) client))
