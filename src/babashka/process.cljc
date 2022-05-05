(ns babashka.process
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]))

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

(defn check
  "Takes a process, waits until is finished and throws if exit code is non-zero."
  [proc]
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
  #?@(:bb []
      :clj [clojure.lang.IBlockingDeref
            (deref [this timeout-ms timeout-value]
                   (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                     @this
                     timeout-value))]))

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

(defn destroy
  "Takes process or map
  with :proc (`java.lang.ProcessBuilder`). Destroys the process and
  returns the input arg."
  [proc]
  (.destroy ^java.lang.Process (:proc proc))
  proc)

(jdk9+-conditional
 nil
 (defn destroy-tree
   "Same as `destroy` but also destroys all descendants. JDK9+ only."
   [proc]
   (let [handle (.toHandle ^java.lang.Process (:proc proc))]
     (run! (fn [^java.lang.ProcessHandle handle]
             (.destroy handle))
           (cons handle (iterator-seq (.iterator (.descendants handle))))))
   proc))

(def ^:private windows?
  (-> (System/getProperty "os.name")
      (str/lower-case)
      (str/includes? "windows")))

(defn- -program-resolver [program]
  ;; this should make life easier and not cause any bugs that weren't there previously
  ;; on exception we just return the program as is
  (try
    (if (fs/relative? program)
      (if-let [f (fs/which program)]
        (str f)
        program)
      program)
    (catch Throwable _ program)))

(defn ^:no-doc default-program-resolver
  [program]
  (if windows?
    (-program-resolver program)
    program))

(def ^:private default-escape
  (if windows? #(str/replace % "\"" "\\\"") identity))

(def ^:dynamic *defaults*
  "Default settings for `process` invocations."
  {:shutdown nil
   :escape default-escape
   :program-resolver default-program-resolver})

(defn- normalize-opts [{:keys [:out :err :in :inherit] :as opts}]
  (cond-> opts
    (and inherit (not out))
    (-> (assoc :out :inherit))
    (and inherit (not err))
    (-> (assoc :err :inherit))
    (and inherit (not in))
    (-> (assoc :in :inherit))
    (instance? java.io.File out)
    (-> (assoc :out-file out)
        (assoc :out :append))
    (instance? java.io.File err)
    (-> (assoc :err-file out)
        (assoc :err :append))))

(defn- build
  (^java.lang.ProcessBuilder [cmd] (build cmd nil))
  (^java.lang.ProcessBuilder [^java.util.List cmd opts]
   (let [;; we assume here that opts are already normalized and merged with
         ;; defaults
         {:keys [:in
                 :out
                 :out-file
                 :err
                 :err-file
                 :dir
                 :env
                 :extra-env
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
              extra-env (add-env extra-env))]
     (case out
       :inherit (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
       :write (.redirectOutput pb (ProcessBuilder$Redirect/to (io/file out-file)))
       :append (.redirectOutput pb (ProcessBuilder$Redirect/appendTo (io/file out-file)))
       nil)
     (case err
       :inherit (.redirectError pb ProcessBuilder$Redirect/INHERIT)
       :write (.redirectError pb (ProcessBuilder$Redirect/to (io/file err-file)))
       :append (.redirectError pb (ProcessBuilder$Redirect/appendTo (io/file err-file)))
       nil)
     (case in
       :inherit (.redirectInput pb ProcessBuilder$Redirect/INHERIT)
       nil)
     pb)))

(defrecord ProcessBuilder [pb opts prev])

(defn pb
  "Returns a process builder (as record)."
  ([cmd] (pb nil cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                (pb cmd opts nil)
                (pb nil cmd opts)))
  ([prev cmd opts]
   (let [opts (merge *defaults* (normalize-opts opts))]
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
  "Takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record with
    - `:proc`: an instance of `java.lang.Process`
    - `:in`, `:err`, `:out`: the process's streams. To obtain a string from
      `:out` or `:err` you will typically use `slurp` or use the `:string`
      option (see below). Slurping those streams will block the current thread
      until the process is finished.
    - `:cmd`: the command that was passed to create the process.
    - `:prev`: previous process record in case of a pipeline.

  The returned record can be passed to `deref`. Doing so will cause the current
  thread to block until the process is finished and will populate `:exit` with
  the exit code.

  Supported options:
   - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to or from the process's corresponding stream. May be set
      to `:inherit` for redirecting to the parent process's corresponding
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
      The `:out` and `:err` options support `:string` for writing to a string
      output. You will need to `deref` the process before accessing the string
      via the process's `:out`.


      For writing output to a file, you can set `:out` and `:err` to a `java.io.File` object, or a keyword:
      - `:write` + an additional `:out-file`/`:err-file` + file to write to the file.
      - `:append` + an additional `:out-file`/`:err-file` + file to append to the file.

   - `:inherit`: if true, sets `:in`, `:out` and `:err` to `:inherit`.
   - `:dir`: working directory.
   - `:env`, `:extra-env`: a map of environment variables. See [Add environment](/README.md#add-environment).
   - `:escape`: function that will applied to each stringified argument. On
      Windows this defaults to prepending a backslash before a double quote. On
      other operating systems it defaults to `identity`.
   - `:pre-start-fn`: a one-argument function that, if present, gets called with a 
      map of process info just before the process is started. Can be useful for debugging 
      or reporting. Any return value from the function is discarded.

      Map contents:
   - `:cmd` - a vector of the tokens of the command to be executed (e.g. `[\"ls\" \"foo\"]`)
   - `:shutdown`: shutdown hook, defaults to `nil`. Takes process
      map. Typically used with `destroy` or `destroy-tree` to ensure long
      running processes are cleaned up on shutdown."
  ([cmd] (process nil cmd nil))
  ([cmd opts] (if (map? cmd) ;; prev
                (process cmd opts nil)
                (process nil cmd opts)))
  ([prev cmd opts]
   (let [opts (merge *defaults* (normalize-opts opts))
         {:keys [:in :in-enc
                 :out :out-enc
                 :err :err-enc
                 :shutdown
                 :pre-start-fn]} opts
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
         _ (when pre-start-fn
             (let [interceptor-map {:cmd cmd}]
               (pre-start-fn interceptor-map)))
         proc (.start pb)
         stdin  (.getOutputStream proc)
         stdout (.getInputStream proc)
         stderr (.getErrorStream proc)
         out (if (and out (or (identical? :string out)
                              (not (keyword? out))))
               (future (copy stdout out out-enc))
               stdout)
         err (if (and err (or (identical? :string err)
                              (not (keyword? err))))
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
  "Convenience macro around `process`. Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`"
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
           [prev# cmd#]
           (if (:proc fcmd#)
             [fcmd# (rest cmd#)]
             [nil cmd#])
           fcmd# (first cmd#)
           [opts# cmd#]
           (if (map? fcmd#)
             [(merge opts# fcmd#) (rest cmd#)]
             [opts# cmd#])]
       (process prev# cmd# opts#))))

(defn sh
  "Convenience function similar to `clojure.java.shell/sh` that sets
  `:out` and `:err` to `:string` by default and blocks. Similar to
  `cjs/sh` it does not check the exit code (this can be done with
  `check`)."
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

(def ^:private graal?
  (boolean (resolve 'org.graalvm.nativeimage.ProcessProperties)))

(defmacro ^:no-doc
  if-graal [then else]
  (if graal?
    then else))

(defn exec
  "Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images."
  ([cmd] (exec cmd nil))
  ([cmd {:keys [escape env extra-env]
         :or {escape default-escape}
         :as opts}]
   (let [cmd (if (and (string? cmd)
                      (not (.exists (io/file cmd))))
               (tokenize cmd)
               cmd)
         str-fn (comp escape str)
         cmd (mapv str-fn cmd)
         cmd (let [program-resolver (:program-resolver opts -program-resolver)
                   [program & args] cmd]
               (into [(program-resolver program)] args))
         [program & args] cmd
         ^java.util.Map env (into (or env (into {} (System/getenv))) extra-env)]
     (if-graal
         (org.graalvm.nativeimage.ProcessProperties/exec (fs/path program) (into-array String args) env)
       (throw (ex-info "exec is not support in non-GraalVM environments" {:cmd cmd}))))))
