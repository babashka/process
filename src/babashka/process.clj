(ns babashka.process
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
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

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- debug [& strs]
  (binding [*out* *err*]
    (println (str/join " " strs))))

(defn process
  ([args] (process args nil))
  ([args opts] (if (map? args)
                 (process args opts nil)
                 (process nil args opts)))
  ([prev args {:keys [:err
                      :in :in-enc
                      :out :out-enc
                      :dir
                      :env]
               :or {dir (System/getProperty "user.dir")}}]
   (let [in (or in (:out prev))
         args (mapv str args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              dir (.directory (io/file dir))
              env (set-env env)
              (identical? err :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in  :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)]
     (when in
       ;; wrap in future, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
       (future
         (with-open [os stdin]
           (io/copy in os :encoding in-enc))))
     (when-not (keyword? out)
       (future (io/copy stdout out :encoding out-enc)))
     (let [exit (delay (.waitFor proc))
           res {:proc proc
                :exit exit
                :in stdin
                :args args
                :out stdout
                :err stderr}]
       res))))

(defn wait
  ([proc] (wait proc {}))
  ([proc opts]
   (let [exit-code @(:exit proc)]
     (if (and (pos? exit-code)
              (:throw opts))
       (let [err (slurp (:err proc))]
         (throw (ex-info (if (string? err) err
                             "failed")
                         (assoc proc :type ::error))))
       proc))))
