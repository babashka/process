(ns babashka.process
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]))

(ns-unmap *ns* 'Process)
(ns-unmap *ns* 'ProcessBuilder)

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
  ^java.lang.ProcessBuilder [^java.lang.ProcessBuilder pb env]
  (doto (.environment pb)
    (.clear)
    (.putAll (as-string-map env)))
  pb)

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- debug [& strs]
  (binding [*out* *err*]
    (println (str/join " " strs))))

(defn check [proc]
  (let [proc @proc
        exit-code (:exit proc)
        err (:err proc)]
    (if (not (zero? exit-code))
      (let [err (if (string? err)
                  err
                  (slurp (:err proc)))]
        (throw (ex-info (if (string? err)
                          err
                          "failed")
                        (assoc proc :type ::error))))
      proc)))

(defrecord Process [^java.lang.Process proc exit in out err prev cmd]
  clojure.lang.IDeref
  (deref [this]
    (let [exit-code (.waitFor proc)
          out (if (future? out) @out out)
          err (if (future? err) @err err)]
      (assoc this
             :exit exit-code
             :out out
             :err err))))

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

(defmacro jdk9+-conditional [pre-9 post-8]
  (if (identical? ::ex (try (import 'java.lang.ProcessHandle)
                            (catch Exception _ ::ex)))
    pre-9
    post-8))

(jdk9+-conditional
 (defn- default-shutdown-hook [proc]
   (.destroy ^java.lang.Process (:proc proc)))
 (defn- default-shutdown-hook [proc]
   (let [handle (.toHandle ^java.lang.Process (:proc proc))]
     (run! (fn [^java.lang.ProcessHandle handle]
             (.destroy handle))
           (cons handle (iterator-seq (.iterator (.descendants handle))))))))

(def ^:dynamic *default-shutdown-hook* default-shutdown-hook)

(def windows? (-> (System/getProperty "os.name")
                  (str/lower-case)
                  (str/includes? "windows")))

(def ^:dynamic *default-escape-fn*
  (if windows? #(str/replace % "\"" "\\\"") identity))

(defn- ^java.lang.ProcessBuilder build
  ([cmd] (build cmd nil))
  ([^java.util.List cmd {:keys [:in
                                :out
                                :err
                                :dir
                                :env
                                :inherit
                                :escape]}]
   (let [escape-fn (or escape *default-escape-fn*)
         str-fn (comp escape-fn str)
         cmd (mapv str-fn cmd)
         pb (cond-> (java.lang.ProcessBuilder. ^java.util.List cmd)
              dir (.directory (io/file dir))
              env (set-env env)
              (or inherit
                  (identical? err :inherit))
              (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
              (or inherit
                  (identical? out :inherit))
              (.redirectOutput java.lang.ProcessBuilder$Redirect/INHERIT)
              (or inherit
                  (identical? in  :inherit))
              (.redirectInput java.lang.ProcessBuilder$Redirect/INHERIT))]
     pb)))

(defrecord ProcessBuilder [pb opts])

(defn pb
  ([cmd] (pb cmd nil))
  ([cmd opts]
   (->ProcessBuilder (build cmd opts)
                     opts)))

(defn copy [in out encoding]
  (let [[out post-fn] (if (keyword? out)
                        (case :string
                          [(java.io.StringWriter.) str])
                        [out identity])]
    (io/copy in out :encoding encoding)
    (post-fn out)))

(defn process
  ([cmd] (process cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                (process cmd opts nil)
                (process nil cmd opts)))
  ([prev cmd {:keys [:in :in-enc
                     :out :out-enc
                     :err :err-enc
                     :shutdown :inherit] :as opts}]
   (let [shutdown (or shutdown *default-shutdown-hook*)
         in (or in (:out prev))
         ^java.lang.ProcessBuilder pb
         (if (instance? java.lang.ProcessBuilder cmd)
           cmd
           (build cmd opts))
         cmd (vec (.command pb))
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)
         out (if (and out (not (or inherit (identical? :inherit out))))
               (future (copy stdout out out-enc))
               stdout)
         err (if (and err (not (or inherit (identical? :inherit err))))
               (future (copy stderr err err-enc))
               stderr)]
     ;; wrap in futures, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
     (when (and in (not (or inherit (identical? :inherit in))))
       (future (with-open [stdin stdin] ;; needed to close stdin after writing
                 (io/copy in stdin :encoding in-enc))))
     (let [;; bb doesn't support map->Process at the moment
           res (->Process proc
                          nil
                          stdin
                          out
                          err
                          prev
                          cmd)]
       (-> (Runtime/getRuntime)
           (.addShutdownHook (Thread. (fn [] (shutdown res)))))
       res))))

(defn start [pb]
  (process (:pb pb) (:opts pb)))

(jdk9+-conditional
 (defn pipeline
   "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders."
   ([proc]
    (if-let [prev (:prev proc)]
      (conj (pipeline prev) proc)
      [proc])))
 (defn pipeline
    "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders."
    ([proc]
     (if-let [prev (:prev proc)]
       (conj (pipeline prev) proc)
       [proc]))
    ([pb & pbs]
     (let [pbs (cons pb pbs)
           opts (map :opts pbs)
           pbs (map :pb pbs)
           procs (java.lang.ProcessBuilder/startPipeline pbs)
           pbs+opts+procs (map vector pbs opts procs)]
       (-> (reduce (fn [{:keys [:prev :procs]}
                        [pb opts proc]]
                     (let [shutdown (or (:shutdown opts) *default-shutdown-hook*)
                           cmd (.command ^java.lang.ProcessBuilder pb)
                           new-proc (proc->Process proc cmd prev)
                           new-procs (conj procs new-proc)]
                       (-> (Runtime/getRuntime)
                           (.addShutdownHook (Thread.
                                              (fn []
                                                (shutdown new-proc)))))
                       {:prev new-proc :procs new-procs}))
                   {:prev nil :procs []}
                   pbs+opts+procs)
           :procs)))))

(defn- process-unquote [arg]
  (let [f (first arg)]
    (if (and (symbol? f) (= "unquote" (name f)))
      (second arg)
      arg)))

(defn- format-arg [arg]
  (cond
    (seq? arg) (process-unquote arg)
    :else (list 'quote arg)))

(defmacro $
  [& args]
  (let [opts (meta &form)
        cmd (mapv format-arg args)]
    `(let [cmd# ~cmd
           [prev# cmd#]
           (if-let [p# (first cmd#)]
             (if #_(instance? Process p#) (:proc p#) ;; workaround for sci#432
                 [p# (rest cmd#)]
                 [nil cmd#])
             [nil cmd#])
           #_#_[opts# cmd#]
           (if-let [o# (first cmd#)]
             (if (map? o#)
               [o# (rest cmd#)]
               [nil cmd#])
             [nil cmd#])]
       (process prev# cmd# ~opts))))

#_(defmacro my-foo [env]
    (with-meta '($ bash -c "echo $FOO")
      {:env env}))

;; user=> (def x 10)
;; #'user/x
;; user=> (-> (my-foo {:FOO x}) :out slurp)
;; "10\n"
