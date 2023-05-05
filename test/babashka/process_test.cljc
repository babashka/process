(ns babashka.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :refer [tokenize process check sh $ pb start] :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

(defn- *find-bb
  "Find bb on path else in current directory.
  Return unresolved bb, it is the job of babashka process, at least on Windows to resolve the exe."
  []
  (or (and (fs/which "bb") "bb")
      (and (fs/which "./bb") "./bb")))

(println "Testing clojure version:" (clojure-version))
(println "Calling babashka as:" (if-let [bb (*find-bb)]
                                  (format "%s (resolves to: %s)" bb (fs/which bb))
                                  "<not found>"))

(def ^:private wd
  "Wee dummy script location. Understands that these tests are run from babashka/process or babashka."
  (->> ["process/script/wd.clj"
        "script/wd.clj"]
       (filter fs/exists?)
       first))

(def ^:private os
  "Sometimes we need to know if we are running on macOS, in those cases fs/windows? does not cut it"
  (condp re-find (str/lower-case (System/getProperty "os.name"))
    #"win" :win
    #"mac" :mac
    #"(nix|nux|aix)" :unix
    #"sunos" :solaris
    :unknown))

(def ^:private always-present-env-vars
  "Even when requesting an empty environment, some OSes do not return an empty environment"
  {:mac ["__CF_USER_TEXT_ENCODING"]
   :win ["SystemRoot"]})

(defn- ols
  "Return s with line separators converted for current operating system"
  [s]
  (str/replace s "\n" (System/getProperty "line.separator")))

(defn- resolve-exe
  "For the purposes of these tests, we sometimes need to expect how babashka process will resolve an exe for :cmd."
  [exe]
  (if (fs/windows?)
    (-> exe fs/which str)
    exe))

(defn- find-bb
  "Tests launch an os-agnostic bb script that emits/behaves in ways useful
  to exercising babashka.process. Any test that uses bb should find it via
  this function.

  Babashka proper also runs these tests under the jvm and native-image.
  For babashka proper jvm tests, bb does not exist in the CI environment.
  This function allows jvm tests to be skipped without error in this scenario.
  This is fine because jvm tests are simply a pre-cursor/quick-way to run tests
  before they are repeated for native-image where any test failures will ultimately
  be caught."
  []
  (or (*find-bb)
      (if (= "jvm" (System/getenv "BABASHKA_TEST_ENV"))
        (println "WARNING: Skipping test because bb not found in path or current dir.")
        (throw (ex-info "ERROR: bb not found in path or current dir" {})))))

(deftest tokenize-test
  (is (= [] (tokenize "")))
  (is (= ["hello"] (tokenize "hello")))
  (is (= ["hello" "world"] (tokenize "  hello   world ")))
  (is (= ["foo   bar" "a" "b" "c" "the d"] (tokenize "\"foo   bar\"    a b c \"the d\"")))
  (is (= ["foo \"  bar" "a" "b" "c" "the d"] (tokenize "\"foo \\\"  bar\"    a b c \"the d\"")))
  (is (= ["echo" "foo bar"] (tokenize "echo 'foo bar'")))
  (is (= ["echo" "{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}"]
         (tokenize "echo '{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}'")))
  (is (= ["c:\\Users\\borkdude\\bin\\graal.bat" "1" "2" "3"]
         (tokenize "c:\\Users\\borkdude\\bin\\graal.bat 1 2 3")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["xxx[dude]xxx"]
         (tokenize "xxx[\"dude\"]xxx")))
  (is (= ["some=something else"]
         (tokenize "some=\"something else\"")))
  (is (= ["bash" "-c" "echo 'two words' | wc -w"]
         (tokenize "bash -c \"echo 'two words' | wc -w\""))))

#?(:bb nil
   :clj
   (deftest parse-args-test
     (when-let [bb (find-bb)]
       (let [norm (p/parse-args [(p/process (format "%s %s :out hello" bb wd)) "cat"])]
         (is (instance? babashka.process.Process (:prev norm)))
         (is (= ["cat"] (:cmd norm))))
       (let [norm (p/parse-args [(p/process (format "%s %s :out hello" bb wd))
                                 ["cat"] {:out :string}])]
         (is (instance? babashka.process.Process (:prev norm)))
         (is (= ["cat"] (:cmd norm)))
         (is (= {:out :string} (:opts norm)))))
     (is (= ["foo" "bar" "baz"] (:cmd (p/parse-args ["foo bar" "baz"]))))
     (let [norm (p/parse-args [{:out :string} "foo bar" "baz"])]
       (is (= ["foo" "bar" "baz"] (:cmd norm)))
       (is (= {:out :string} (:opts norm))))
     (testing "existing file invocation"
       (let [args ["README.md" "a" "b" "c"]]
         (is (= args (:cmd (p/parse-args args))))))
     (testing "prev may be nil"
       (is (= ["echo" "hello"] (:cmd (p/parse-args [nil ["echo hello"]])))))))

(deftest process-wait-realize-test
  (testing "By default process returns string out and err, returning the exit
  code in a delay. Waiting for the process to end happens through realizing the
  delay. Waiting also happens implicitly by not specifying :stream, since
  realizing :out or :err needs the underlying process to finish."
    (when-let [bb (find-bb)]
      (let [res (process [bb wd ":out" "hello"])
            out (slurp (:out res))
            err (slurp (:err res))
            checked (check res) ;; check should return process with :exit code
            ;; populated
            exit (:exit checked)]
        (is (= (ols "hello\n") out))
        (is (string? err))
        (is (str/blank? err))
        (is (number? exit))
        (is (zero? exit))))))

(deftest process-wait-realize-with-stdin-test
  (testing "When specifying :out and :err both a non-strings, the process keeps
  running. :in is the stdin of the process to which we can write. Calling close
  on that stream closes stdin, so a program like cat will exit. We wait for the
  process to exit by realizing the exit delay."
    (when-let [bb (find-bb)]
      (let [res (process [(symbol bb) (symbol wd) ':upper] {:err :inherit})
            _ (is (true? (.isAlive (:proc res))))
            in (:in res)
            w (io/writer in)
            _ (binding [*out* w]
                (println "hello"))
            _ (.close in)
            exit (:exit @res)
            _ (is (zero? exit))
            _ (is (false? (.isAlive (:proc res))))
            out-stream (:out res)]
        (is (= (ols "HELLO\n") (slurp out-stream)))))))

(deftest process-copy-input-from-string-test
  (when-let [bb (find-bb)]
    (let [proc (process [(symbol bb) (symbol wd) ':upper] {:in "foo"})
          out (:out proc)
          ret (:exit @proc)]
      (is (= 0 ret))
      (is (= (ols "FOO\n") (slurp out))))))

(deftest process-redirect-err-out-test
  (when-let [bb (find-bb)]
    (let [test-cmd (format "%s -cp '' %s :out :to-stdout :err :to-stderr"
                           bb wd)]
      (testing "baseline"
        (let [res @(process {:out :string :err :string} test-cmd)]
          (is (= (ols ":to-stdout\n") (:out res)))
          (is (= (ols ":to-stderr\n") (:err res)))))
      (testing "redirect"
        (let [res @(process {:out :string :err :out} test-cmd)
              out-string (:out res)
              err-null-input-stream (:err res)]
          (is (= (ols ":to-stdout\n:to-stderr\n") out-string))
          (is (instance? java.io.InputStream err-null-input-stream))
          (is (= 0 (.available err-null-input-stream)))
          (is (= -1 (.read err-null-input-stream))))))))

(deftest process-copy-to-out-test
  (when-let [bb (find-bb)]
    (let [s (with-out-str
              @(process [(symbol bb) (symbol wd) ':upper] {:in "foo" :out *out*}))]
      (is (= (ols "FOO\n") s)))))

(deftest process-copy-stderr-to-out-test
  (when-let [bb (find-bb)]
    (let [s (with-out-str
              (-> (process [(symbol bb) (symbol wd) ':err 'foo] {:err *out*})
                  deref :exit))]
      (is (= (ols "foo\n") s)))))

(deftest process-chaining-test
  (when-let [bb (find-bb)]
    (is (= (ols "README.md\n")
           (-> (process [bb wd ":out" "foo" ":out" "README.md" ":out" "bar"])
               (process [bb wd ":grep" "README.md"]) :out slurp)))
    (is (= (ols "README.md\n")
           (-> (sh [bb wd ":out" "foo" ":out" "README.md" ":out" "bar"])
               (sh [bb wd ":grep" "README.md"]) :out)))))

(deftest process-dir-option-test
  (when-let [bb (find-bb)]
    (let [out (-> (process [bb wd ":ls" "."]) :out slurp)]
      (is (str/includes? out "README.md"))
      (is (= out
             (-> (process [bb wd ":ls" "."] {:dir "."}) :out slurp))))

    (let [subdir (if (= "script/wd.clj" wd) ;; handle running from babashka vs babashka/process
                   "test/babashka"
                   "process/test/babashka")
          rel (str/replace subdir #"[^/]+" "..")
          rel-wd (str rel "/" wd)
          rel-bb (if (= "./bb" bb)
                   (str rel "/bb")
                   bb)]
      (let [out1 (-> (process [bb wd ":ls" "."]) :out slurp)
            out2 (-> (process [rel-bb rel-wd ":ls" "."] {:dir subdir})
                     :out slurp)]
        (is (str/includes? out1 "README.md"))
        (is (str/includes? out2 "process_test.cljc"))
        (is (not= out1 out2))))))

(deftest process-env-option-test
  (when-let [bb (find-bb)]
    (testing "request an empty env"
      ;; using -cp "" for bb here, otherwise it will expect JAVA_HOME env var to be set
      (let [vars (-> (process [bb "-cp" "" wd ":env"] {:env {}})
                     :out
                     slurp
                     edn/read-string)
            expected-vars (os always-present-env-vars)]
        (is (= expected-vars (keys vars)))))
    (testing "add to existing env"
      (let [out (-> (sh (format "%s %s :env" bb wd) {:extra-env {:FOO "BAR"}})
                    :out)]
        (is (str/includes? out "PATH"))
        (is (str/includes? out "\"FOO\" \"BAR\""))))
    (testing "request a specific env"
      ;; using -cp "" for bb here, otherwise it will expect JAVA_HOME env var to be set
      (let [vars (-> (process [bb "-cp" "" wd ":env"]
                              {:env {"SOME_VAR" "SOME_VAL"
                                     :keyword_var "KWVARVAL"
                                     "keyword_val" :keyword-val}})
                     :out
                     slurp
                     edn/read-string)
            added-vars (apply dissoc vars (os always-present-env-vars))]
        (is (= {"SOME_VAR" "SOME_VAL"
                "keyword_val" ":keyword-val"
                "keyword_var" "KWVARVAL"}
               added-vars))))))

(deftest process-check-throws-on-non-zero-exit-test
  (when-let [bb (find-bb)]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"error123"
          (-> (process (format "%s %s :err error123 :exit 1" bb wd))
              (check)))
        "with :err string")
    (is (thrown?
          clojure.lang.ExceptionInfo #"failed"
          (-> (process (format "%s %s :exit 1" bb wd))
              (check)))
        "With no :err string")
    (is (thrown?
          clojure.lang.ExceptionInfo #"failed"
          (-> (process {:err *err*} (format "%s %s :exit 1" bb wd))
              (check)))
        "With :err set to *err*")
    (testing "and the exception"
      (let [command [bb wd ":exit" "1"]]
        (try
          (-> (process command)
              (check))
          (catch clojure.lang.ExceptionInfo e
            (testing "contains the process arguments"
              (is (= (assoc command 0 (-> command first resolve-exe))
                     (:cmd (ex-data e)))))
            (testing "and contains a babashka process type"
              (is (= :babashka.process/error (:type (ex-data e)))))))))))

#_{:clj-kondo/ignore [:unused-binding]}
(deftest process-dollar-macro-test
  (when-let [bb (find-bb)]
    (let [config {:a 1}]
      (is (= (ols "{:a 1}\n") (-> ($ ~(symbol bb) ~(symbol wd) :out ~config) :out slurp)))
      (let [sw (java.io.StringWriter.)]
        (is (= (ols "{:a 1}\n") (do (-> ^{:out sw}
                                        ($ ~(symbol bb) ~(symbol wd) :out ~config)
                                        deref)
                                    (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= (ols "{:a 1}\n") (do (-> ($ ~{:out sw} ~(symbol bb) ~(symbol wd) :out ~config)
                                        deref)
                                    (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= (ols "{:a 1}\n") (do (-> ($ {:out sw} ~(symbol bb) ~(symbol wd) :out ~config)
                                        deref)
                                    (str sw))))))))

(deftest process-same-as-pb-start-test
  (when-let [bb (find-bb)]
    (let [cmd [bb wd ":ls" "."]
          out (-> (process cmd) :out slurp)]
      (is (and (string? out) (not (str/blank? out))))
      (is (str/includes? out "README.md"))
      (is (= out (-> (pb cmd) (start) :out slurp))))))

(deftest process-out-to-string-test
  (when-let [bb (find-bb)]
    (is (= (ols "hello\n") (-> (process [bb wd ":out" "hello"] {:out :string})
                               check
                               :out)))))

(deftest process-tokenization-test
  (when-let [bb (find-bb)]
    (is (= (ols "hello\n") (-> (process (format "%s %s :out hello" bb wd) {:out :string})
                               check
                               :out)))
    ;; This bit of awkwardness might be avoidable.
    ;; But if we needing to test ($ "literal string") maybe not.
    (is (= (ols "hello\n") (-> (case bb
                                 "bb"
                                 (case wd
                                   "script/wd.clj" ^{:out :string} ($ "bb script/wd.clj :out hello")
                                   "process/script/wd.clj" ^{:out :string} ($ "bb process/script/wd.clj :out hello"))
                                 "./bb"
                                 (case wd
                                   "script/wd.clj" ^{:out :string} ($ "./bb script/wd.clj :out hello")
                                   "process/script/wd.clj" ^{:out :string} ($ "./bb process/script/wd.clj :out hello") ))
                               check
                               :out)))
    (is (= (ols "hello\n") (-> (sh (format "%s %s :out hello" bb wd))
                               :out)))))

(deftest process-space-in-cmd-test
  (when-let [bb (find-bb)]
    (let [proc @(p/process [(str bb " ") wd ":out" "hello"] {:out :string})]
      (is (= (ols "hello\n") (:out proc)))
      (is (zero? (:exit proc))))))

#?(:bb nil ;; skip longer running test when running form babashka proper
   :clj
   (deftest process-deref-timeout-test
     (when-let [bb (find-bb)]
       (is (= ::timeout (deref (process [bb wd ":sleep" "500"]) 250 ::timeout)))
       (is (= 0 (:exit (deref (process [bb wd]) 250 nil)))))))

(deftest shell-test
  (when-let [bb (find-bb)]
    (is (str/includes? (:out (p/shell {:out :string} (format "%s %s :out hello" bb wd))) "hello"))
    (is (str/includes? (-> (p/shell {:out :string} (format "%s %s :out hello" bb wd))
                           (p/shell {:out :string } (format "%s %s :upper" bb wd))
                           :out)
                       "HELLO"))
    (is (= 1 (do (p/shell {:continue true} (format "%s %s :exit 1" bb wd)) 1)))))


#_{:clj-kondo/ignore [:unused-binding]}
(deftest dollar-pipe-test
  (when-let [bb (find-bb)]
    (is (= (ols "HELLO\n")
           (-> ($ ~(symbol bb) ~(symbol wd) :out hello)
               ($ {:out :string} ~(symbol bb) ~(symbol wd) :upper) deref :out)))
    (is (= (ols "HELLO\n")
           (-> ($ ~(symbol bb) ~(symbol wd) :out hello)
               ^{:out :string} ($ ~(symbol bb) (symbol wd) :upper) deref :out)))
    (is (= (ols "hello\n")
           (-> ($ ~(symbol bb) ~(symbol wd) :out goodbye :out hello)
               ($ ~(symbol bb) ~(symbol wd) :grep hello) deref :out slurp)))))

(deftest redirect-file-test
  (when-let [bb (find-bb)]
    (fs/with-temp-dir [tmp {}]
      (let [out (fs/file tmp "out.txt")]
        @(p/process (format "%s %s :out hello" bb wd)
                    {:out :write :out-file out})
        (is (= (ols "hello\n") (slurp out)))
        @(p/process (format "%s %s :out goodbye" bb wd)
                    {:out :append :out-file out})
        (is (= (ols "hello\ngoodbye\n") (slurp out)))))
    (fs/with-temp-dir [tmp {}]
      (let [out (fs/file tmp "err.txt")]
        @(p/process (format "%s %s :err 'err,hello'" bb wd)
                    {:err :write :err-file out})
        (is (= (ols "err,hello\n") (slurp out)))
        @(p/process (format "%s %s :err 'grrr-oodbye'" bb wd)
                    {:err :append :err-file out})
        (is (= (ols "err,hello\ngrrr-oodbye\n") (slurp out)))))))

(deftest pprint-test
  ;; #?(:bb nil ;; in bb we already required the babashka.process.pprint namespace
  ;;    :clj
  ;;    (testing "calling pprint on a process without requiring pprint namespace causes exception (ambiguous on pprint/simple-dispatch multimethod)"
  ;;      (is (thrown-with-msg? IllegalArgumentException #"Multiple methods in multimethod 'simple-dispatch' match dispatch value"
  ;;                            (-> (process "cat missing-file.txt") pprint)))))
  (when-let [bb (find-bb)]
    (testing "after requiring pprint namespace, process gets pprinted as a map"
      (do
        (require '[babashka.process] :reload '[babashka.process.pprint] :reload)
        (is (str/includes? (with-out-str (-> (process (format "%s %s :out hello" bb wd)) pprint)) ":proc"))))))

(deftest pre-start-fn-test
  (when-let [bb (find-bb)]
    (testing "a print fn option gets executed just before process is started"
      (let [p {:pre-start-fn #(apply println "Running" (:cmd %))}
            resolved-bb (resolve-exe bb)]
        (is (= (ols (format "Running %s %s :out hello1\n" resolved-bb wd))
               (with-out-str (process (format "%s %s :out hello1" bb wd) p))))
        (is (= (ols (format "Running %s %s :out hello2\n" resolved-bb wd))
               (with-out-str (-> (pb [bb wd ":out" "hello2"] p) start))))
        (is (= (ols (format "Running %s %s :exit 32\n" resolved-bb wd))
               (with-out-str (sh (format "%s %s :exit 32" bb wd) p))))))))

(defmacro ^:private jdk9+ []
  (if (identical? ::pre-jdk9
                  (try (import 'java.lang.ProcessHandle)
                       (catch Exception _ ::pre-jdk9)))
    '(do
       (require '[babashka.process :refer [pipeline]])
       (deftest pipeline-prejdk9-test
         (when-let [bb (find-bb)]
           (testing "pipeline returns processes nested with ->"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb wd ":out" "foo"]
                       [resolved-bb wd ":upper"]]
                      (map :cmd (pipeline (-> (process [bb wd ":out" "foo"])
                                              (process [bb wd ":upper"])))))))))))
    '(do
       (require '[babashka.process :refer [pipeline pb]])
       (deftest inherit-test
         (when-let [bb (find-bb)]
           (let [proc (process (format "%s %s :out ''" bb wd) {:shutdown p/destroy-tree
                                                               :inherit true})
                 null-input-stream-class (class (:out proc))
                 null-output-stream-class (class (:in proc))]
             (is (= null-input-stream-class (class (:err proc))))
             (let [x (process [bb wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :in "foo"})]
               (is (not= null-output-stream-class (class (:in x))))
               (is (= null-input-stream-class (class (:out x))))
               (is (= null-input-stream-class (class (:err x)))))
             (let [x (process [bb wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :out :string})]
               (is (= null-output-stream-class (class (:in x))))
               (is (not= null-input-stream-class (class (:out x))))
               (is (= null-input-stream-class (class (:err x)))))
             (let [x (process [bb wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :err :string})]
               (is (= null-output-stream-class (class (:in x))))
               (is (= null-input-stream-class (class (:out x))))
               (is (not= null-input-stream-class (class (:err x))))))))
       (deftest pipeline-test
         (when-let [bb (find-bb)]
           (testing "pipeline returns processes nested with ->"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb wd ":out" "foo"]
                       [resolved-bb wd ":upper"]]
                      (map :cmd (pipeline (-> (process [bb wd ":out" "foo"])
                                              (process [bb wd ":upper"]))))))))
           (testing "pipeline returns processes created with pb"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb wd ":out" "foo"]
                       [resolved-bb wd ":upper"]]
                      (map :cmd (pipeline (pb [bb wd ":out" "foo"])
                                          (pb [bb wd ":upper"])))))))
           (testing "pbs can be chained with ->"
             (let [chain (-> (pb [bb wd ":out" "hello"])
                             (pb [bb wd ":upper"] {:out :string}) start deref)
                   resolved-bb (resolve-exe bb)]
               (is (= (ols "HELLO\n") (slurp (:out chain))))
               (is (= [[resolved-bb wd ":out" "hello"]
                       [resolved-bb wd ":upper"]] (map :cmd (pipeline chain))))))))
       (deftest exit-fn-test
         (when-let [bb (find-bb)]
           (let [exit-code (promise)]
             (process [bb wd ":exit" "42"]
                      {:exit-fn (fn [proc] (deliver exit-code (:exit proc)))})
             (is (= 42 @exit-code))))))))

(jdk9+)

(deftest alive-lives-test
  (when-let [bb (find-bb)]
    (let [{:keys [in] :as res} (process [(symbol bb) (symbol wd) ':upper])]
      (is (true? (p/alive? res)))
      (.close in)
      @res
      (is (false? (p/alive? res))))))
