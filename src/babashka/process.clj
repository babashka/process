(ns babashka.process
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(ns-unmap *ns* 'Process)
(ns-unmap *ns* 'ProcessBuilder)

(set! *warn-on-reflection* true)

(defn tokenize
  "Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with `'` or `\"`."
  [s]
  (loop [s (java.io.StringReader. s)
         in-double-quotes? false
         in-single-quotes? false
         buf (java.io.StringWriter.)
         parsed []]
    (let [c (.read s)]
      (cond
        (= -1 c) (if (pos? (count (str buf)))
                   (conj parsed (str buf))
                   parsed)
        (= 39 c) ;; single-quotes
        (if in-single-quotes?
        ;; exit single-quoted string
          (recur s in-double-quotes? false (java.io.StringWriter.) (conj parsed (str buf)))
        ;; enter single-quoted string
          (recur s in-double-quotes? true buf parsed))

        (= 92 c)
        (let [escaped (.read s)
              buf (if (and in-double-quotes?
                           (= 34 escaped))
                    (doto buf (.write escaped))
                    (doto buf
                      (.write c)
                      (.write escaped)))]
          (recur s in-double-quotes? in-single-quotes? buf parsed))

        (and (not in-single-quotes?) (= 34 c)) ;; double quote
        (if in-double-quotes?
        ;; exit double-quoted string
          (recur s false in-single-quotes? (java.io.StringWriter.) (conj parsed (str buf)))
        ;; enter double-quoted string
          (recur s true in-single-quotes? buf parsed))

        (and (not in-double-quotes?)
             (not in-single-quotes?)
             (Character/isWhitespace c))
        (recur s in-double-quotes? in-single-quotes? (java.io.StringWriter.)
               (let [bs (str buf)]
                 (cond-> parsed
                   (not (str/blank? bs)) (conj bs))))
        :else (do
                (.write buf c)
                (recur s in-double-quotes? in-single-quotes? buf parsed))))))

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

(defn- add-env
  "Adds environment for a ProcessBuilder instance.
  Returns instance to participate in the thread-first macro."
  ^java.lang.ProcessBuilder [^java.lang.ProcessBuilder pb env]
  (doto (.environment pb)
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
             :err err)))
  clojure.lang.IBlockingDeref
  (deref [this timeout-ms timeout-value]
    (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
      @this
      timeout-value)))

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

(defmacro ^:private jdk9+-conditional [pre-9 post-8]
  (if (identical? ::ex (try (import 'java.lang.ProcessHandle)
                            (catch Exception _ ::ex)))
    pre-9
    post-8))

(defn destroy [proc]
  (.destroy ^java.lang.Process (:proc proc))
  proc)

(jdk9+-conditional
 nil
 (defn destroy-tree [proc]
   (let [handle (.toHandle ^java.lang.Process (:proc proc))]
     (run! (fn [^java.lang.ProcessHandle handle]
             (.destroy handle))
           (cons handle (iterator-seq (.iterator (.descendants handle))))))
   proc))

(def ^:private windows?
  (-> (System/getProperty "os.name")
      (str/lower-case)
      (str/includes? "windows")))

(defn default-program-resolver [program]
  (if (and windows? (fs/relative? program))
    (if-let [f (fs/which program)]
      (str f)
      program)
    program))

(def ^:dynamic *defaults*
  {:shutdown nil
   :escape (if windows? #(str/replace % "\"" "\\\"") identity)
   :program-resolver default-program-resolver})

(defn- ^java.lang.ProcessBuilder build
  ([cmd] (build cmd nil))
  ([^java.util.List cmd opts]
   (let [opts (merge *defaults* opts)
         {:keys [:in
                 :out
                 :err
                 :dir
                 :env
                 :extra-env
                 :inherit
                 :escape]} opts
         str-fn (comp escape str)
         cmd (mapv str-fn cmd)
         cmd (if-let [program-resolver (:program-resolver opts)]
               (let [[program & args] cmd]
                 (into [(program-resolver program)] args))
               cmd)
         pb (cond-> (java.lang.ProcessBuilder. ^java.util.List cmd)
              dir (.directory (io/file dir))
              env (set-env env)
              extra-env (add-env extra-env)
              (or (and (not err) inherit)
                  (identical? err :inherit))
              (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
              (or (and (not out) inherit)
                  (identical? out :inherit))
              (.redirectOutput java.lang.ProcessBuilder$Redirect/INHERIT)
              (or (and (not in) inherit)
                  (identical? in  :inherit))
              (.redirectInput java.lang.ProcessBuilder$Redirect/INHERIT))]
     pb)))

(defrecord ProcessBuilder [pb opts prev])

(defn pb
  ([cmd] (pb nil cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                (pb cmd opts nil)
                (pb nil cmd opts)))
  ([prev cmd opts]
   (let [opts (merge *defaults* opts)]
     (->ProcessBuilder (build cmd opts)
                       opts
                       prev))))

(defn- copy [in out encoding]
  (let [[out post-fn] (if (keyword? out)
                        (case out
                          :string [(java.io.StringWriter.) str])
                        [out identity])]
    (io/copy in out :encoding encoding)
    (post-fn out)))

(defn process
  ([cmd] (process nil cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                (process cmd opts nil)
                (process nil cmd opts)))
  ([prev cmd opts]
   (let [opts (merge *defaults* opts)
         {:keys [:in :in-enc
                 :out :out-enc
                 :err :err-enc
                 :shutdown]} opts
         in (or in (:out prev))
         cmd (if (and (string? cmd)
                      (not (.exists (io/file cmd))))
               (tokenize cmd)
               cmd)
         ^java.lang.ProcessBuilder pb
         (if (instance? java.lang.ProcessBuilder cmd)
           cmd
           (build cmd opts))
         cmd (vec (.command pb))
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)
         out (if (and out (not (identical? :inherit out)))
               (future (copy stdout out out-enc))
               stdout)
         err (if (and err (not (identical? :inherit err)))
               (future (copy stderr err err-enc))
               stderr)]
     ;; wrap in futures, see https://github.com/clojure/clojure/commit/7def88afe28221ad78f8d045ddbd87b5230cb03e
     (when (and in (not (identical? :inherit in)))
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
       (when shutdown
         (-> (Runtime/getRuntime)
             (.addShutdownHook (Thread. (fn [] (shutdown res))))))
       res))))

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
                     (let [shutdown (:shutdown opts)
                           cmd (.command ^java.lang.ProcessBuilder pb)
                           new-proc (proc->Process proc cmd prev)
                           new-procs (conj procs new-proc)]
                       (when shutdown
                         (-> (Runtime/getRuntime)
                             (.addShutdownHook (Thread.
                                                (fn []
                                                  (shutdown new-proc))))))
                       {:prev new-proc :procs new-procs}))
                   {:prev nil :procs []}
                   pbs+opts+procs)
           :procs)))))

(defn start [pb]
  (let [pipe (pipeline pb)]
    (if (= 1 (count pipe))
      (process (:pb pb) (:opts pb))
      (last (apply pipeline pipe)))))

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
        farg (first args)
        args (if (and (= 1 (count args))
                      (and (string? farg)
                           (not (.exists (io/file farg)))))
               (tokenize farg)
               args)
        farg (first args)
        marg? (map? farg)
        cmd (if marg?
              (vec (cons farg (mapv format-arg (rest args))))
              (mapv format-arg args))]
    `(let [cmd# ~cmd
           opts# ~opts
           fcmd# (first cmd#)
           [opts# cmd#]
           (if (map? fcmd#)
             [(merge opts# fcmd#) (rest cmd#)]
             [opts# cmd#])
           fcmd# (first cmd#)
           [prev# cmd#]
           (if fcmd#
             (if (:proc fcmd#)
               [fcmd# (rest cmd#)]
               [nil cmd#])
             [nil cmd#])]
       ;; (prn (list 'process prev# cmd# opts#))
       (process prev# cmd# opts#))))

(defn sh
  ([cmd] (sh cmd nil))
  ([cmd opts]
   (let [[prev cmd opts] (if (:proc cmd)
                           [cmd opts nil]
                           [nil cmd opts])]
     @(process prev cmd (merge {:out :string
                                :err :string} opts))))
  ([prev cmd opts]
   @(process prev cmd (merge {:out :string
                              :err :string} opts))))
