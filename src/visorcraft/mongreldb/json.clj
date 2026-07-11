(ns visorcraft.mongreldb.json
  "Minimal JSON codec used internally by the client.

  Encodes/decodes maps, vectors, numbers, booleans, strings, nil, and keywords -
  the exact shape the daemon's JSON API uses - without a third-party dependency.
  This is intentionally narrow: it is not a general-purpose JSON library."
  (:import [java.util Map List]
           [clojure.lang Keyword Ratio IPersistentVector IPersistentMap
            IPersistentSet IPersistentList ISeq]
           [java.nio.charset StandardCharsets]
           [java.math BigInteger BigDecimal]))

;; ── Encoder ────────────────────────────────────────────────────────────────

(defn- emit-number [^StringBuilder sb n]
  (cond
    (instance? Double n) (let [d ^Double n]
                           (if (or (Double/isNaN d) (Double/isInfinite d))
                             (.append sb "null")
                             (.append sb (str d))))
    (instance? Float n)  (let [d (double ^Float n)]
                           (if (or (Double/isNaN d) (Double/isInfinite d))
                             (.append sb "null")
                             (.append sb (str n))))
    (instance? Ratio n)  (.append sb (str (double n)))
    :else                (.append sb (str n))))

(defn- hex-char [^long b]
  (char (if (< b 10) (+ (int \0) b) (+ (int \A) (- b 10)))))

(defn- emit-string [^StringBuilder sb s]
  (let [^String s (if (keyword? s) (name s) (str s))]
    (.append sb \")
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          \backspace (.append sb "\\b")
          \formfeed (.append sb "\\f")
          (if (< (int c) 0x20)
            (do (.append sb "\\u")
                (let [v (int c)]
                  (doseq [shift [12 8 4 0]]
                    (.append sb (str (hex-char (bit-and 0x0f (bit-shift-right v shift))))))))
            (.append sb c)))))
    (.append sb \")))

(declare emit)

(defn- emit-map [^StringBuilder sb m]
  (.append sb \{)
  (loop [first? true entries (seq m)]
    (when (seq entries)
      (let [[k v] (first entries)]
        (when-not first? (.append sb \,))
        (emit-string sb k)
        (.append sb \:)
        (emit sb v)
        (recur false (next entries)))))
  (.append sb \}))

(defn- emit-coll [^StringBuilder sb coll]
  (.append sb \[)
  (loop [first? true xs (seq coll)]
    (when (seq xs)
      (when-not first? (.append sb \,))
      (emit sb (first xs))
      (recur false (next xs))))
  (.append sb \]))

(defn- emit [^StringBuilder sb v]
  (cond
    (nil? v)              (.append sb "null")
    (instance? Boolean v) (.append sb (str v))
    (instance? Map v)     (emit-map sb v)
    (instance? Number v)  (emit-number sb v)
    (instance? Keyword v) (emit-string sb v)
    (instance? String v)  (emit-string sb v)
    (instance? List v)    (emit-coll sb v)
    (instance? ISeq v)    (emit-coll sb v)
    (instance? IPersistentVector v) (emit-coll sb v)
    (instance? IPersistentSet v)    (emit-coll sb v)
    :else                 (emit-string sb v)))

(defn to-bytes
  "Encode a value to a UTF-8 JSON byte array."
  [value]
  (let [sb (StringBuilder.)]
    (emit sb value)
    (.getBytes (.toString sb) StandardCharsets/UTF_8)))

;; ── Decoder (recursive descent) ────────────────────────────────────────────
;;
;; The reader is a mutable Java array of one int (position) over an immutable
;; source String, threaded through the parse functions. Uses a single-element
;; int array for cheap mutation without atoms.

(defn- ^:private parse-error [^String msg pos]
  (throw (ex-info (str "mongreldb: " msg " at " pos)
                  {:mongreldb/parse-error true})))

(defn- skip-ws [^String src ^ints pos]
  (loop []
    (when (and (< (aget pos 0) (.length src))
               (let [c (.charAt src (aget pos 0))]
                 (or (= c \space) (= c \tab) (= c \newline) (= c \return))))
      (aset pos 0 (inc (aget pos 0)))
      (recur))))

(defn- read-string* [^String src ^ints pos]
  ;; assume current char is the opening quote
  (aset pos 0 (inc (aget pos 0)))
  (let [sb (StringBuilder.)]
    (loop []
      (if (>= (aget pos 0) (.length src))
        (parse-error "unterminated string" (aget pos 0))
        (let [c (.charAt src (aget pos 0))]
          (aset pos 0 (inc (aget pos 0)))
          (cond
            (= c \") (.toString sb)
            (= c \\) (if (>= (aget pos 0) (.length src))
                       (parse-error "unterminated escape" (aget pos 0))
                       (let [e (.charAt src (aget pos 0))]
                         (aset pos 0 (inc (aget pos 0)))
                         (condp = e
                           \" (.append sb \")
                           \\ (.append sb \\)
                           \/ (.append sb \/)
                           \n (.append sb \newline)
                           \r (.append sb \return)
                           \t (.append sb \tab)
                           \b (.append sb \backspace)
                           \f (.append sb \formfeed)
                           \u (let [hex (.substring src (aget pos 0) (+ (aget pos 0) 4))]
                                (aset pos 0 (+ (aget pos 0) 4))
                                (.append sb (char (Integer/parseInt hex 16))))
                           (parse-error (str "bad escape \\" e) (dec (aget pos 0))))
                         (recur)))
            :else (do (.append sb c) (recur))))))))

(declare read-value*)

(defn- read-bool* [^String src ^ints pos]
  (if (.startsWith src "true" (aget pos 0))
    (do (aset pos 0 (+ (aget pos 0) 4)) true)
    (if (.startsWith src "false" (aget pos 0))
      (do (aset pos 0 (+ (aget pos 0) 5)) false)
      (parse-error "invalid literal" (aget pos 0)))))

(defn- read-null* [^String src ^ints pos]
  (if (.startsWith src "null" (aget pos 0))
    (do (aset pos 0 (+ (aget pos 0) 4)) nil)
    (parse-error "invalid literal" (aget pos 0))))

(defn- read-number* [^String src ^ints pos]
  (let [start (aget pos 0)
        len (.length src)]
    (when (and (< (aget pos 0) len) (= (.charAt src (aget pos 0)) \-))
      (aset pos 0 (inc (aget pos 0))))
    (loop []
      (when (< (aget pos 0) len)
        (let [c (.charAt src (aget pos 0))]
          (when (or (and (>= (int c) (int \0)) (<= (int c) (int \9)))
                    (= c \.) (= c \e) (= c \E) (= c \+) (= c \-))
            (aset pos 0 (inc (aget pos 0)))
            (recur)))))
    (let [num (.substring src start (aget pos 0))]
      (if (or (neg? (.indexOf num ".")) (and (neg? (.indexOf num "e")) (neg? (.indexOf num "E"))))
        (try (Long/parseLong num) (catch Exception _ (BigInteger. num)))
        (Double/parseDouble num)))))

(defn- read-array* [^String src ^ints pos]
  (aset pos 0 (inc (aget pos 0))) ;; consume [
  (skip-ws src pos)
  (if (= (.charAt src (aget pos 0)) \])
    (do (aset pos 0 (inc (aget pos 0))) [])
    (loop [acc (transient [])]
      (let [v (read-value* src pos)]
        (skip-ws src pos)
        (let [acc (conj! acc v)
              c (.charAt src (aget pos 0))]
          (aset pos 0 (inc (aget pos 0)))
          (case c
            \, (recur acc)
            \] (persistent! acc)
            (parse-error "expected ',' or ']'" (dec (aget pos 0)))))))))

(defn- read-object* [^String src ^ints pos]
  (aset pos 0 (inc (aget pos 0))) ;; consume {
  (skip-ws src pos)
  (if (= (.charAt src (aget pos 0)) \})
    (do (aset pos 0 (inc (aget pos 0))) {})
    (loop [acc (transient {})]
      (skip-ws src pos)
      (let [k (read-string* src pos)]
        (skip-ws src pos)
        (let [sep (.charAt src (aget pos 0))]
          (aset pos 0 (inc (aget pos 0)))
          (when (not= sep \:)
            (parse-error "expected ':'" (dec (aget pos 0)))))
        (let [v (read-value* src pos)]
          (skip-ws src pos)
          (let [acc (assoc! acc k v)
                c (.charAt src (aget pos 0))]
            (aset pos 0 (inc (aget pos 0)))
            (case c
              \, (recur acc)
              \} (persistent! acc)
              (parse-error "expected ',' or '}'" (dec (aget pos 0))))))))))

(defn- read-value* [^String src ^ints pos]
  (skip-ws src pos)
  (if (>= (aget pos 0) (.length src))
    (parse-error "unexpected end of JSON" (aget pos 0))
    (let [c (.charAt src (aget pos 0))]
      (case c
        \{ (read-object* src pos)
        \[ (read-array* src pos)
        \" (read-string* src pos)
        \t (read-bool* src pos)
        \f (read-bool* src pos)
        \n (read-null* src pos)
        (read-number* src pos)))))

(defn parse
  "Parse a UTF-8 JSON byte array into Clojure data (maps with string keys,
  vectors, numbers, booleans, nil)."
  [^bytes body]
  (let [src (String. body StandardCharsets/UTF_8)
        pos (int-array 1 0)]
    (skip-ws src pos)
    (let [v (read-value* src pos)]
      (skip-ws src pos)
      (when (< (aget pos 0) (.length src))
        (parse-error "trailing JSON content" (aget pos 0)))
      v)))
