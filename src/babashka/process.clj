(ns babashka.process
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]))

(ns-unmap *ns* 'Process)

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

(defn check [proc]
  (let [exit-code (:exit @proc)]
    (if (not (zero? exit-code))
      (let [err (slurp (:err proc))]
        (throw (ex-info (if (string? err)
                          err
                          "failed")
                        (assoc proc :type ::error))))
      proc)))

(defrecord Process [^java.lang.Process proc exit in out err prev cmd]
  clojure.lang.IDeref
  (deref [this]
    (let [exit-code (.waitFor proc)]
      (assoc this :exit exit-code))))

(defmethod print-method Process [proc ^java.io.Writer w]
  (.write w (pr-str (into {} proc))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- proc->Process [^java.lang.Process proc cmd prev]
  (let [stdin  (.getOutputStream proc)
        stdout (.getInputStream proc)
        stderr (.getErrorStream proc)]
    (->Process proc
               nil
               stdin
               stdout
               stderr
               prev
               cmd)))

(defmacro ^:private jdk9+ []
  (if (identical? ::ex (try (import 'java.lang.ProcessHandle)
                            (catch Exception _ ::ex)))
    '(defn pipeline
       "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders."
       ([proc]
        (if-let [prev (:prev proc)]
          (conj (pipeline prev) proc)
          [proc])))
    '(defn pipeline
      "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders."
      ([proc]
       (if-let [prev (:prev proc)]
         (conj (pipeline prev) proc)
         [proc]))
      ([pb & pbs]
       (let [pbs (cons pb pbs)
             procs (ProcessBuilder/startPipeline pbs)
             pb+procs (map vector pbs procs)]
         (-> (reduce (fn [{:keys [:prev :procs]}
                          [pb proc]]
                       (let [cmd (.command ^java.lang.ProcessBuilder pb)
                             new-prev (proc->Process proc cmd prev)
                             new-procs (conj procs new-prev)]
                         {:prev new-prev :procs new-procs}))
                     {:prev nil :procs []}
                     pb+procs)
             :procs))))))

(jdk9+)

(defn ^java.lang.ProcessBuilder pb
  ([cmd] (pb cmd nil))
  ([^java.util.List cmd {:keys [:in
                                :out
                                :err
                                :dir
                                :env]}]
   (let [cmd (mapv str cmd)
         pb (cond-> (ProcessBuilder. ^java.util.List cmd)
              dir (.directory (io/file dir))
              env (set-env env)
              (identical? err :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in  :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))]
     pb)))

(defn process
  ([cmd] (process cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                    (process cmd opts nil)
                    (process nil cmd opts)))
  ([prev cmd {:keys [:in  :in-enc
                         :out :out-enc
                         :err :err-enc] :as opts}]
   (let [in (or in (:out prev))
         cmd (mapv str cmd)
         pb (pb cmd opts)
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)]
     ;; wrap in futures, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
     (when (and in (not (identical? :inherit out)))
       (future (with-open [stdin stdin] ;; needed to close stdin after writing
                 (io/copy in stdin :encoding in-enc))))
     (when (and out (not (identical? :inherit out)))
       (future (io/copy stdout out :encoding out-enc)))
     (when (and err (not (identical? :inherit err)))
       (future (io/copy stderr err :encoding err-enc)))
     (let [;; bb doesn't support map->Process at the moment
           res (->Process proc
                          nil
                          stdin
                          stdout
                          stderr
                          prev
                          cmd)]
       res))))

(defn- process-unquote [arg]
  (let [f (first arg)]
    (if (and (symbol? f) (= "unquote" (name f)))
      (second arg)
      arg)))

(defn- format-arg [arg]
  (cond
    (symbol? arg) (str arg)
    (seq? arg) (process-unquote arg)
    (string? arg) arg
    :else (pr-str arg)))

(defmacro $
  [& args]
  (let [opts (-> (drop-while #(not (identical? ::opts %)) args)
                 second)
        opts (if (seq? opts) (process-unquote opts) opts)
        args (take-while #(not (identical? ::opts %)) args)
        cmd (mapv format-arg args)]
    `(process ~cmd ~opts)))
