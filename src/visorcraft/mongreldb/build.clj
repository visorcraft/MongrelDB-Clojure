(ns visorcraft.mongreldb.build
  "Build helper that compiles the tiny Java exception classes into
  `target/classes` so they are on the classpath for the Clojure client/tests.

  Run with: clojure -X:build"
  (:import [javax.tools ToolProvider]
           [java.io File]
           [java.nio.file Files Paths CopyOption StandardCopyOption]))

(defn ^:private java-files
  "Recursively list every .java file under `root`."
  [^File root]
  (seq (filter #(and (.isFile ^File %)
                     (.endsWith (.getName ^File %) ".java"))
               (file-seq root))))

(defn compile-java
  "Compile the Java exception sources from `java-src` into `target/classes`.
  Idempotent: creates the output dir if missing. Returns the count compiled."
  [_opts]
  (let [src-dir (File. "java-src")
        out-dir (File. "target/classes")]
    (when-not (.exists src-dir)
      (throw (ex-info "java-src/ not found; run from the repo root" {})))
    (.mkdirs out-dir)
    (let [sources (or (java-files src-dir)
                      (throw (ex-info "no .java files under java-src/" {})))
          compiler (ToolProvider/getSystemJavaCompiler)
          args (into-array String
                            (->> [ "-d" (.getAbsolutePath out-dir)]
                                 (into (mapv #(.getAbsolutePath ^File %) sources))))
          rc (.run compiler nil nil nil args)]
      (when-not (zero? rc)
        (throw (ex-info "javac failed" {:rc rc})))
      (println "Compiled" (count sources) "Java source files to target/classes")))
  {:compiled true})
