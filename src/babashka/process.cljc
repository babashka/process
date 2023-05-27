(ns babashka.process
  "Clojure library for shelling out / spawning sub-processes.
  If you are not yet familiar with the API, start reading the
  docstrings for `process` and `shell`."
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
        (if in-double-quotes?
          (recur s in-double-quotes? false (doto buf
                                             (.write c)) parsed)
          (if in-single-quotes?
            (recur s in-double-quotes? false (java.io.StringWriter.) (conj parsed (str buf)))
            (recur s in-double-quotes? true buf parsed)))
        (= 92 c) ;; the \\ escape character
        (let [escaped (.read s)
              buf (if (and in-double-quotes?
                           (= 34 escaped)) ;; double quote
                    (doto buf (.write escaped))
                    (doto buf
                      (.write c)
                      (.write escaped)))]
          (recur s in-double-quotes? in-single-quotes? buf parsed))

        (and (not in-single-quotes?) (= 34 c)) ;; double quote
        (if in-double-quotes?
          ;; exit double-quoted string
          (recur s false in-single-quotes? buf parsed)
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
      (let [err (cond
                  (string? err)
                  err

                  (instance? java.io.InputStream err)
                  (slurp err)

                  :else
                  nil)]
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
  (let [stdin (.getOutputStream proc)
        stdout (.getInputStream proc)
        stderr (.getErrorStream proc)]
    (->Process proc
               nil
               stdin
               stdout
               stderr
               prev
               cmd)))

(defmacro ^:private if-before-jdk8 [pre-9 post-8]
  (if (identical? ::ex (try (import 'java.lang.ProcessHandle)
                            (catch Exception _ ::ex)))
    pre-9
    post-8))

(defn destroy
  "Destroys the process and returns the input arg. Takes process or map
  with :proc (`java.lang.ProcessBuilder`). "
  [proc]
  (.destroy ^java.lang.Process (:proc proc))
  proc)

(if-before-jdk8
 (def destroy-tree destroy)
 (defn destroy-tree
   "Same as `destroy` but also destroys all descendants. JDK9+
  only. Falls back to `destroy` on older JVM versions."
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

(defn- -program-resolver [{:keys [program dir]}]
  ;; this should make life easier and not cause any bugs that weren't there previously
  ;; on exception we just return the program as is
  (try
    (if (fs/relative? program)
      (if-let [f (fs/which (if dir
                             (-> (fs/file dir program) fs/absolutize)
                             program))]
        (str f)
        program)
      program)
    (catch Throwable _ program)))

(defn ^:no-doc default-program-resolver
  [{:keys [program] :as opts}]
  (if windows?
    (-program-resolver opts)
    program))

(def ^:private default-escape
  (if windows? #(str/replace % "\"" "\\\"") identity))

(def ^:dynamic *defaults*
  "Dynamic var containing overridable default options. Use
  `alter-var-root` to change permanently or `binding` to change temporarily."
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
    (or (instance? java.io.File out)
        (string? out))
    (-> (assoc :out-file (io/file out))
        (assoc :out :write))
    (or (instance? java.io.File err)
        (string? err))
    (-> (assoc :err-file (io/file err))
        (assoc :err :write))))

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
                 (into [(program-resolver {:program program :dir dir})] args))
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
       :out (.redirectErrorStream pb true)
       :inherit (.redirectError pb ProcessBuilder$Redirect/INHERIT)
       :write (.redirectError pb (ProcessBuilder$Redirect/to (io/file err-file)))
       :append (.redirectError pb (ProcessBuilder$Redirect/appendTo (io/file err-file)))
       nil)
     (case in
       :inherit (.redirectInput pb ProcessBuilder$Redirect/INHERIT)
       nil)
     pb)))

(defrecord ProcessBuilder [pb opts prev])

(defn parse-args
  "Parses arguments to `process` to map with:

  * `:prev`: a (previous) process whose input is piped into the current process
  * `:cmd`: a vector of command line argument strings
  * `:opts`: options map

  Note that this function bridges the legacy `[cmds ?opts]` syntax to
  the newer recommended syntax `[?opts & args]` and therefore looks
  unnecessarily complex."
  [args]
  (let [arg-count (count args)
        maybe-prev (first args)
        args (rest args)
        [prev args] (if (or (instance? Process maybe-prev)
                            (instance? ProcessBuilder maybe-prev)
                            (and (nil? maybe-prev)
                                 (sequential? (first args))))
                      [maybe-prev args]
                      [nil (cons maybe-prev args)])
        ;; we've parsed the input process, now assume the first argument is either an opts map, or a sequential
        maybe-opts (first args)
        args (rest args)
        [opts args] (cond (or (nil? maybe-opts) (map? maybe-opts))
                          [maybe-opts args args]
                          (sequential? maybe-opts)
                          ;; flatten command structure
                          [nil (into (vec maybe-opts) args)]
                          (string? maybe-opts)
                          [nil (cons maybe-opts args)]
                          :else [nil (cons maybe-opts args)])
        [args opts] (cond opts
                          [args opts]
                          (and (= (+ 2 (if prev 1 0)) arg-count)
                               (map? (last args)))
                          [(butlast args) (last args)]
                          ;; no options found
                          :else [args opts])
        args (let [args (map str args)
                   fst (first args)
                   rst (rest args)]
               (vec (into (if (fs/exists? fst)
                            [fst]
                            (if fst
                              (tokenize fst)
                              fst)) rst)))
        prev (:prev opts prev)]
    {:prev prev
     :cmd (or (:cmd opts) args)
     :opts opts}))

(defn pb
  "Returns a process builder (as record)."
  [& args]
  (let [{:keys [cmd opts prev]} (parse-args args)]
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

(defn process*
  "Same as with `process` but called with parsed arguments (the result from `parse-args`)"
  [{:keys [prev cmd opts]}]
  (let [opts (merge *defaults* (normalize-opts opts))
        prev-in (:out prev)
        opt-in (:in opts)
        opts (assoc opts :in
                    (cond (not opt-in) prev-in
                          (= :inherit opt-in) (or prev-in opt-in)
                          :else opt-in))
        {:keys [in in-enc
                out out-enc
                err err-enc
                shutdown
                pre-start-fn
                exit-fn]} opts
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
        stdin (.getOutputStream proc)
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
      (when exit-fn
        (if-before-jdk8
         (throw (ex-info "The `:exit-fn` option is not support on JDK 8 and lower." res))
         (-> (.onExit proc)
             (.thenRun (fn []
                         (exit-fn @res))))))
      res)))

(defn process
  "Creates a child process. Takes a command (vector of strings or
  objects that will be turned into strings) and optionally a map of
  options.

  Returns: a record with:
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
   - `:cmd`: a vector of strings. A single string can be tokenized into a vector of strings with `tokenize`.
      Overrides the variadic `args` argument.
   - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to from the process's corresponding stream.
      May be set to `:inherit` for redirecting to the parent process's corresponding
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
      For redirecting to Clojure's `*in*`, `*out*` or `*err*` stream, set
      the corresponding option accordingly.
      The `:out` and `:err` options support `:string` for writing to a string
      output. You will need to `deref` the process before accessing the string
      via the process's `:out`.
      To redirect `:err` to `:out`, specify `:err :out`.
      For writing output to a file, you can set `:out` and `:err` to a `java.io.File` object, or a keyword:
       - `:write` + an additional `:out-file`/`:err-file` + file to write to the file.
       - `:append` + an additional `:out-file`/`:err-file` + file to append to the file.
   - `:prev`: output from `:prev` will be piped to the input of this process. Overrides `:in`.
   - `:inherit`: if true, sets `:in`, `:out` and `:err` to `:inherit`.
   - `:dir`: working directory.
   - `:env`, `:extra-env`: a map of environment variables. See [Add environment](/README.md#add-environment).
   - `:escape`: function that will applied to each stringified argument. On
      Windows this defaults to prepending a backslash before a double quote. On
      other operating systems it defaults to `identity`.
   - `:pre-start-fn`: a one-argument function that, if present, gets called with a
      map of process info just before the process is started. Can be useful for debugging
      or reporting. Any return value from the function is discarded. Map contents:
      - `:cmd` - a vector of the tokens of the command to be executed (e.g. `[\"ls\" \"foo\"]`)
   - `:shutdown`: shutdown hook, defaults to `nil`. Takes process
      map. Typically used with `destroy` or `destroy-tree` to ensure long
      running processes are cleaned up on shutdown.
   - `:exit-fn`: a function which is executed upon exit. Receives process map as argument. Only supported in JDK11+."
  {:arglists '([opts? & args])}
  [& args]
  (process* (parse-args args)))

(if-before-jdk8
 (defn pipeline
   "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with `->` or `pipeline`.
  - When passing two or more process builders created with `pb`: creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  "
   ([proc]
    (if-let [prev (:prev proc)]
      (conj (pipeline prev) proc)
      [proc])))
 (defn pipeline
   "Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with `->` or `pipeline`.
  - When passing two or more process builders created with `pb`: creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  "
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

(defn start
  "Takes a process builder, calls start and returns a process (as record)."
  [pb]
  (let [pipe (pipeline pb)]
    (if (= 1 (count pipe))
      (process* {:cmd (:pb pb) :opts (:opts pb)})
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
  {:arglists '([opts? & args])}
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
       (process* {:prev prev# :cmd cmd# :opts opts#}))))

(defn sh
  "Convenience function similar to `clojure.java.shell/sh` that sets
  `:out` and `:err` to `:string` by default and blocks. Similar to
  `cjs/sh` it does not check the exit code (this can be done with
  `check`)."
  {:arglists '([opts? & args])}
  [& args]
  (let [{:keys [opts cmd prev]} (parse-args args)
        opts (merge {:out :string
                     :err :string} opts)]
    @(process* {:cmd cmd :opts opts :prev prev})))

(def ^:private has-exec?
  (boolean (try (.getMethod ^Class
                 (resolve 'org.graalvm.nativeimage.ProcessProperties) "exec"
                            (into-array [java.nio.file.Path (Class/forName "[Ljava.lang.String;") java.util.Map]))
                (catch Exception _ false))))

(defmacro ^:no-doc
  if-has-exec [then else]
  (if has-exec?
    then else))

(defn exec
  "Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images (which includes babashka).

  Supported `opts`
  - `:arg0`: override first argument (the executable). No-op on Windows.
  - `:env`,`:extra-env`,`:escape`,`:pre-start-fn` : see `process`."
  {:arglists '([opts? & args])}
  [& args]
  (let [{:keys [cmd opts]} (parse-args args)]
    (let [{:keys [escape env extra-env pre-start-fn]
           :or {escape (:escape *defaults*)
                pre-start-fn (:pre-start-fn *defaults*)}
           :as opts} opts
          str-fn (comp escape str)
          cmd (mapv str-fn cmd)
          arg0 (or (:arg0 opts)
                   (first cmd))
          program-resolver (:program-resolver opts
                                              ;; we don't look at the *defaults*
                                              ;; here since on non-Windows it
                                              ;; does nothing, we need to always resolve the full path
                                              -program-resolver)
          cmd (let [[program & args] cmd]
                (into [(program-resolver {:program program})] args))
          _ (when pre-start-fn
              (let [interceptor-map {:cmd cmd}]
                (pre-start-fn interceptor-map)))
          [program & args] cmd
          args (cons arg0 args)
          ^java.util.Map env (into (or (as-string-map env)
                                       (into {} (System/getenv)))
                                   (as-string-map extra-env))]
      (if-has-exec
       (org.graalvm.nativeimage.ProcessProperties/exec (fs/path program)
                                                       (into-array String args)
                                                       env)
       (throw (ex-info "exec is not supported in non-GraalVM environments" {:cmd cmd}))))))

(def ^:private default-shell-opts
  {:in :inherit
   :out :inherit
   :err :inherit
   :shutdown destroy-tree})

(defn shell
  "Convenience function around `process` that was originally in `babashka.tasks`.
  Defaults to inheriting I/O: input is read and output is printed
  while the process runs. Throws on non-zero exit codes. Kills all
  subprocesses on shutdown. Optional options map can be passed as the
  first argument, followed by multiple command line arguments. The
  first command line argument is automatically tokenized. Counter to
  what the name of this function may suggest, it does not start a
  new (bash, etc.) shell, it just shells out to a program. As such, it
  does not support bash syntax like `ls *.clj`.

  Examples:

  - `(shell \"ls -la\")` ;; `\"ls -la\"` is tokenized as `[\"ls\" \"-la\"]`
  - `(shell {:out \"/tmp/log.txt\"} \"git commit -m\" \"WIP\")` ;; `\"git commit -m\"` is tokenized as `[\"git\" \"commit\" \"-m\"]` and `\"WIP\"` is an additional argument

  Also see the `shell` entry in the babashka book [here](https://book.babashka.org/#_shell)."
  {:arglists '([opts? & args])}
  [& args]
  (let [{:keys [opts] :as args} (parse-args args)]
    (let [proc (process* (assoc args :opts (merge default-shell-opts opts)))
          proc (deref proc)]
      (if (:continue opts)
        proc
        (check proc)))))

(defn alive?
  "Returns `true` if the process is still running and false otherwise."
  [p]
  (.isAlive ^java.lang.Process (:proc p)))

#?(:bb nil
   :clj
   (when (contains? (loaded-libs) 'clojure.pprint) ;; pprint was already loaded, e.g. by nREPL
     (require '[babashka.process.pprint])))
