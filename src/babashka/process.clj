(ns babashka.process
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder$Redirect]))

(set! *warn-on-reflection* true)

(defn- as-string-map
  "Helper to coerce a Clojure map with keyword keys into something coerceable to Map<String,String>

  Stringifies keyword keys, but otherwise doesn't try to do anything clever with values"
  [m]
  (if (map? m)
    (into {} (map (fn [[k v]] [(str (if (keyword? k) (name k) k)) (str v)])) m)
    m))

(defn- set-env
  "Sets environment for a ProcessBuilder instance.
  Returns instance to participate in the thread-first macro."
  ^ProcessBuilder [^ProcessBuilder pb env]
  (doto (.environment pb)
    (.clear)
    (.putAll (as-string-map env)))
  pb)

(defn process
  ([args] (process args nil))
  ([args opts] (if (map? args)
                 (process args opts nil)
                 (process nil args opts)))
  ([prev args {:keys [:err
                      :in :in-enc
                      :out :out-enc
                      :dir
                      :env
                      :throw]
               :or {out :string
                    err :string
                    dir (System/getProperty "user.dir")
                    throw true}}]
   (let [in (or in (:out prev))
         args (mapv str args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              dir (.directory (io/file dir))
              env (set-env env)
              (identical? err :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in  :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)
         stdin (.getOutputStream proc)]
     (when in
       ;; wrap in future, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
       (future
         (with-open [os stdin]
           (io/copy in os :encoding in-enc))))
     (let [exit (delay (.waitFor proc))
           res {:proc proc
                :exit exit
                :in stdin}
           res (if (identical? out :string)
                 (assoc res :out (slurp (.getInputStream proc)))
                 (assoc res :out (.getInputStream proc)))
           err (if (identical? err :string)
                 (slurp (.getErrorStream proc))
                 (.getErrorStream proc))
           res (assoc res :err err)]
       (when-not (keyword? out)
         (io/copy (.getInputStream proc) out :encoding out-enc))
       (if throw
         (if (and (or (not (.isAlive proc))
                      (or (string? err)
                          (identical? :string out)))
                  (pos? @exit))
           (throw (ex-info (if (string? err) err
                               "failed")
                           (assoc res
                                  :args args
                                  :type ::error)))
           res)
         res)))))

;;;; Examples

(comment
  ;; slurp output stream
  (-> (process ["ls"]) :out)
  ;;=> "LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"

  (-> (process ["ls"] {:dir "test/babashka"}) :out)
  ;;=> "process_test.clj\n"

  (-> (process ["ls"] {:dir "src/babashka"}) :out)
  ;;=> "process.clj\n"

  ;; return output as string
  (-> (process ["ls"] {:out :string}) :out)

  ;; redirect output to stdout
  (do (-> (process ["ls"] {:out :inherit})) nil)

  ;; redirect output from one process to input of another process
  (let [is (-> (process ["ls"]) :out)]
    (process ["cat"] {:in is
                      :out :inherit})
    nil)

  )
