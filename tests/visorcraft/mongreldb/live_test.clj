(ns visorcraft.mongreldb.live-test
  "Live integration tests against a real mongreldb-server daemon.

  These are live tests: they boot a real mongreldb-server daemon and exercise
  the full client surface against it. They skip automatically when no daemon is
  available.

  The harness resolves the daemon binary in this order:
    1. the MONGRELDB_SERVER env var (path to the server binary).
    2. a prebuilt binary at ./bin/mongreldb-server (downloaded by the CI
       workflow or `make server`).
    3. mongreldb-server on PATH.

  If no binary is available, the suite is skipped. Set MONGRELDB_URL to point
  at an already-running daemon to skip the boot and connect directly.

  Run with:
    clojure -M:test -m visorcraft.mongreldb.live-test     # full suite
    clojure -M:test -m visorcraft.mongreldb.live-test --offline   # offline only"
  (:require [clojure.test :as test :refer [deftest is testing use-fixtures
                                           run-tests successful?]]
            [visorcraft.mongreldb.core :as mdb]
            [visorcraft.mongreldb.query :as q]
            [visorcraft.mongreldb.transaction :as txn])
  (:import [java.io File]
           [java.net ServerSocket]
           [java.nio.charset StandardCharsets]
           [dev.visorcraft.mongreldb MongrelDBException NotFoundException
            ConflictException QueryException]))

;; ── Daemon lifecycle ───────────────────────────────────────────────────────

(def ^:private daemon-state
  "Atom holding the daemon lifecycle state. Keys:
    :client  the connected client, or nil when none booted
    :process the java.lang.Process, or nil
    :log-path the path to the server log, or nil"
  (atom {:client nil :process nil :log-path nil}))

(defn- resolve-server-binary []
  (let [env (System/getenv "MONGRELDB_SERVER")]
    (when (and env (not (empty? env)))
      (let [f (File. env)]
        (when (and (.isFile f) (.canExecute f))
          (.getAbsolutePath f))))
    (let [local (File. "bin/mongreldb-server")]
      (when (and (.isFile local) (.canExecute local))
        (.getAbsolutePath local)))
    (let [path (System/getenv "PATH")]
      (when path
        (->> (.split path File/pathSeparator)
             (some (fn [dir]
                     (let [f (File. dir "mongreldb-server")]
                       (when (and (.isFile f) (.canExecute f))
                         (.getAbsolutePath f))))))))))

(defn- free-port []
  (with-open [s (ServerSocket. 0)]
    (.getLocalPort s)))

(defn- reachable? [url]
  (try
    (let [c (mdb/connect {:url url :token (System/getenv "MONGRELDB_TOKEN")})]
      (mdb/health c))
    (catch Exception _ false)))

(defn- wait-for-health [url max-seconds]
  (let [deadline (+ (System/currentTimeMillis) (* max-seconds 1000))]
    (loop []
      (or (reachable? url)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 500)
            (recur))))))

(defn- dump-log [path]
  (when (and path (.exists (File. path)))
    (println "--- mongreldb-server log (" path ") ---")
    (println (slurp path))))

(defn boot-daemon! []
  (let [existing (System/getenv "MONGRELDB_URL")]
    (cond
      (and existing (not (empty? existing)))
      (if (reachable? existing)
        (swap! daemon-state assoc
               :client (mdb/connect {:url existing
                                     :token (System/getenv "MONGRELDB_TOKEN")}))
        (do (println "mongreldb: MONGRELDB_URL=" existing " is not reachable")
            (System/exit 1)))

      :else
      (let [bin (resolve-server-binary)]
        (if (nil? bin)
          (println "--- no mongreldb-server binary: live tests will skip")
          (let [port (free-port)
                data-dir (str (System/getProperty "java.io.tmpdir")
                              File/separator
                              "mongreldb-clj-test-"
                              (format "%x" (System/nanoTime)))
                _ (.mkdirs (File. data-dir))
                url (str "http://127.0.0.1:" port)
                log-path (str (System/getProperty "java.io.tmpdir")
                              File/separator
                              "mongreldb-clj-server-"
                              (format "%x" (System/nanoTime))
                              ".log")]
            (try
              (let [builder (ProcessBuilder. [bin data-dir "--port" (str port)])
                    log-file (File. log-path)
                    _ (.redirectOutput builder log-file)
                    _ (.redirectErrorStream builder true)
                    proc (.start builder)]
                (swap! daemon-state assoc :process proc :log-path log-path)
                (.destroyOnExit proc)
                (when-not (wait-for-health url 40)
                  (dump-log log-path)
                  (println "mongreldb: server did not become healthy")
                  (System/exit 1))
                (swap! daemon-state assoc :client (mdb/connect {:url url})))
              (catch java.io.IOException e
                (println "mongreldb: failed to start server:" (.getMessage e))))))))))

(defn shutdown-daemon! []
  (when-let [proc (:process @daemon-state)]
    (.destroy proc)
    (try (.waitFor proc) (catch InterruptedException _ (.interrupt (Thread/currentThread))))))

;; ── Test helpers ───────────────────────────────────────────────────────────

(defn- client [] (:client @daemon-state))

(defn- unique-table [prefix]
  (str prefix "_" (format "%x" (System/nanoTime))))

(defn- int-col [id name & {:keys [primary_key] :or {primary_key false}}]
  {"id" id "name" name "ty" "int64"
   "primary_key" primary_key "nullable" false})

(defn- float-col [id name]
  {"id" id "name" name "ty" "float64"
   "primary_key" false "nullable" false})

(defn- fresh-table [name & columns]
  (try (mdb/drop-table (client) name) (catch Exception _))
  (mdb/create-table (client) name (vec columns)))

(defn- cell-value
  "Extract a column value from a Kit row's flat cells array
  (shape: [col_id, value, col_id, value, ...])."
  [cells col-id]
  (when (vector? cells)
    (loop [xs cells]
      (when (seq xs)
        (let [[id val] xs]
          (if (= id col-id) val (recur (nnext xs))))))))

(defn- skip-if-no-client []
  (when (nil? (client))
    (println "[SKIP] no mongreldb-server available")
    true))

(defmacro ^:private live-test
  "A deftest that skips when the daemon was not booted."
  [name & body]
  `(deftest ~name
     (when-not (skip-if-no-client)
       (testing ~(str name) ~@body))))

;; ── Live test suite: 14-operation conformance matrix ──────────────────────

(live-test health-returns-true
  (is (true? (mdb/health (client)))))

(live-test create-table-and-count
  (let [name (unique-table "clj_create")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (is (zero? (mdb/count* (client) name)))))

(live-test put-and-count-round-trip
  (let [name (unique-table "clj_put")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (mdb/put (client) name {1 1, 2 99.5})
    (mdb/put (client) name {1 2, 2 150.0})
    (is (= 2 (mdb/count* (client) name)))))

(live-test query-by-primary-key
  (let [name (unique-table "clj_pk")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (mdb/put (client) name {1 42})
    (mdb/put (client) name {1 43})
    (let [[rows] (-> (mdb/query (client) name)
                     (q/where "pk" {:value 42})
                     (q/execute-full))]
      (is (= 1 (count rows)))
      (is (= 42 (cell-value (:cells (first rows)) 1))))))

(live-test query-range-with-friendly-aliases
  (let [name (unique-table "clj_range")]
    (fresh-table name (int-col 1 "id" :primary_key true) (int-col 2 "amount"))
    (mdb/put (client) name {1 1, 2 50})
    (mdb/put (client) name {1 2, 2 120})
    (mdb/put (client) name {1 3, 2 200})
    (let [b (-> (mdb/query (client) name)
                (q/where "range" {:column 2 :min 100 :max 150}))
          [rows truncated] (q/execute-full b)]
      (is (= 1 (count rows)))
      (is (false? truncated))
      (doseq [row rows]
        (is (= 2 (cell-value (:cells row) 1)))
        (let [amt (cell-value (:cells row) 2)]
          (is (>= amt 100))
          (is (<= amt 150)))))))

(live-test upsert-inserts-then-updates
  (let [name (unique-table "clj_upsert")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (mdb/upsert (client) name {1 1, 2 99.5} {2 99.5})
    (is (= 1 (mdb/count* (client) name)))
    (mdb/upsert (client) name {1 1, 2 120.0} {2 120.0})
    (is (= 1 (mdb/count* (client) name)))
    (let [[rows] (-> (mdb/query (client) name)
                     (q/where "pk" {:value 1})
                     (q/execute-full))]
      (is (= 1 (count rows)))
      (is (= 1 (cell-value (:cells (first rows)) 1)))
      (is (= 120.0 (cell-value (:cells (first rows)) 2))))))

(live-test transaction-put-commit
  (let [name (unique-table "clj_txn")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (let [t (-> (mdb/begin (client))
                (txn/put name {1 1})
                (txn/put name {1 2})
                (txn/put name {1 3}))]
      (is (= 3 (txn/count* t)))
      (let [{:keys [results]} (txn/commit t)]
        (is (= 3 (count results)))
        (is (= 3 (mdb/count* (client) name)))))))

(live-test transaction-commit-with-idempotency-key
  (let [name (unique-table "clj_txn_idem")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (let [idem-key (str "order-100-create-" (System/currentTimeMillis))
          {:keys [results]} (-> (mdb/begin (client))
                                (txn/put name {1 100})
                                (txn/commit idem-key))]
      (is (= 1 (count results)))
      (is (= 1 (mdb/count* (client) name)))
      (try
        (-> (mdb/begin (client))
            (txn/put name {1 100})
            (txn/commit idem-key))
        (catch Exception _))
      (is (= 1 (mdb/count* (client) name))))))

(live-test transaction-rollback-discards-ops
  (let [name (unique-table "clj_txn_rb")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (-> (mdb/begin (client))
        (txn/put name {1 1})
        (txn/put name {1 2})
        (txn/rollback))
    (is (zero? (mdb/count* (client) name)))))

(live-test transaction-double-commit-throws
  (let [name (unique-table "clj_txn_double")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (let [t (-> (mdb/begin (client)) (txn/put name {1 1}))]
      (txn/commit t)
      (is (thrown? IllegalStateException (txn/commit t))))))

(live-test delete-by-pk-removes-the-row
  (let [name (unique-table "clj_del")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (mdb/put (client) name {1 5})
    (is (= 1 (mdb/count* (client) name)))
    (mdb/delete-by-pk (client) name 5)
    (is (zero? (mdb/count* (client) name)))))

(live-test sql-insert-increases-count
  (let [name (unique-table "clj_sql")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (is (zero? (mdb/count* (client) name)))
    (mdb/sql (client) (str "INSERT INTO " name " (id, amount) VALUES (77, 7.5)"))
    (is (= 1 (mdb/count* (client) name)))
    (let [rows (mdb/sql (client) (str "SELECT id, amount FROM " name))]
      (when-not (empty? rows)
        (is (= 1 (count rows)))
        (is (= 77 (get (first rows) "id")))))))

(live-test table-names-lists-created-table
  (let [name (unique-table "clj_tables")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (is (some #{name} (mdb/table-names (client))))))

(live-test drop-table-removes-it
  (let [name (unique-table "clj_drop")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (mdb/drop-table (client) name)
    (is (not (some #{name} (mdb/table-names (client)))))))

(live-test schema-includes-created-table
  (let [name (unique-table "clj_schema")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (is (contains? (mdb/schema (client)) name))))

(live-test schema-for-returns-descriptor
  (let [name (unique-table "clj_schema_for")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (let [desc (mdb/schema-for (client) name)]
      (is (map? desc))
      (is (contains? desc "schema_id"))
      (is (vector? (get desc "columns")))
      (is (= 2 (count (get desc "columns")))))))

(live-test compact-all-tables
  (is (map? (mdb/compact (client)))))

(live-test compact-single-table
  (let [name (unique-table "clj_compact")]
    (fresh-table name (int-col 1 "id" :primary_key true))
    (mdb/put (client) name {1 1})
    (is (map? (mdb/compact-table (client) name)))))

(live-test error-on-nonexistent-table-is-not-found
  (let [name (unique-table "clj_missing")]
    (is (thrown? NotFoundException (mdb/schema-for (client) name)))))

(live-test duplicate-put-raises-conflict
  (let [name (unique-table "clj_conflict")]
    (try (mdb/drop-table (client) name) (catch Exception _))
    (mdb/post (client) "/kit/create_table"
              {:name name
               :columns [(int-col 1 "id" :primary_key true)]
               :constraints {:uniques [{:id 1 :name "uq" :columns [1]}]}})
    (mdb/put (client) name {1 1})
    (is (thrown? ConflictException (mdb/put (client) name {1 1})))))

(live-test query-projection-and-limit
  (let [name (unique-table "clj_proj")]
    (fresh-table name (int-col 1 "id" :primary_key true) (float-col 2 "amount"))
    (doseq [i (range 5)] (mdb/put (client) name {1 i, 2 (double i)}))
    (let [[rows] (-> (mdb/query (client) name)
                     (q/projection [1])
                     (q/limit 2)
                     (q/execute-full))]
      (is (= 2 (count rows))))))

;; ── Offline unit tests (no daemon needed) ─────────────────────────────────

(deftest flatten-cells-test
  (let [flat (mdb/flatten-cells {1 "Alice" 3 99.5})
        pairs (partition 2 flat)
        by-id (into {} (map vec pairs))]
    (is (= "Alice" (by-id 1)))
    (is (= 99.5 (by-id 3)))))

(deftest url-path-escape-encodes-slash
  (is (= "a%2Fb" (mdb/url-path-escape "a/b")))
  (is (= "plain" (mdb/url-path-escape "plain")))
  (is (= "a%20b" (mdb/url-path-escape "a b"))))

(deftest query-builder-alias-translation
  (let [norm (q/normalize-condition "range"
                                    {:column 3 :min 100 :max 150
                                     :min_inclusive true :max_inclusive false})]
    (is (= {:column_id 3 :lo 100 :hi 150
            :lo_inclusive true :hi_inclusive false} norm)))
  (let [norm (q/normalize-condition "fm_contains" {:column 2 :value "hi"})]
    (is (= {:column_id 2 :pattern "hi"} norm)))
  (let [norm (q/normalize-condition "pk" {:value 42})]
    (is (= {:value 42} norm))))

(deftest query-builder-build-payload
  (let [b (-> (q/builder nil "orders")
              (q/where "range" {:column 3 :min 100})
              (q/projection [1 2])
              (q/limit 10))
        payload (q/build b)]
    (is (= "orders" (:table payload)))
    (is (= 1 (count (:conditions payload))))
    (is (= {:range {:column_id 3 :lo 100}} (first (:conditions payload))))
    (is (= [1 2] (:projection payload)))
    (is (= 10 (:limit payload)))))

(deftest transaction-already-committed-constant
  (is (= "mongreldb: transaction already committed"
         txn/already-committed)))

;; ── Main: boot, run, shut down ─────────────────────────────────────────────

(defn -main
  "Entry point for `clojure -M:test -m visorcraft.mongreldb.live-test`.
  Pass `--offline` to skip daemon boot and run only the offline unit tests."
  [& args]
  (let [offline? (some #{ "--offline" "-offline" } args)]
    (when-not offline? (boot-daemon!))
    (let [results (binding [test/*load-tests* true]
                    (run-tests 'visorcraft.mongreldb.live-test))]
      (when-not offline? (shutdown-daemon!))
      (System/exit (if (successful? results) 0 1)))))
