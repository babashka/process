(ns babashka.process-exec-test
  "The `babashka.process/exec` fn can only be run from a GraalVM natively
  compiled executable. We have separated exec tests to this namespace
  so that can be easily included/execluded from a test run.

  Because `exec` replaces the existing process, we need to launch an exec
  runner that will be replaced. (We don't want our test runner to replaced).
  Our exec runner source is `test-native/src/babashka/test_native/run_exec.clj`.

  Our exec runner will typically in turn launch our wee dummy script `wd.clj`.

  Various ways which these tests are run:
  - from babashka.process
    - `test:native` task - tests against natively compiled `run_exec.clj`
       (and in turn a natively compiled babashka.process)
    - `test:bb` task - tests under bb using `run_exec.clj` from source against
      `babashka.process` from source
  - from babashka lib tests
    - `BABASHKA_TEST_ENV` is `jvm` - tests are skipped
    - `BABASHKA_TEST_ENV` is not `jvm` (presumably `native`) - tests are run under bb twice both using
      `run-exec.clj` from source:
      - once against natively compiled babashka.process within bb
      - a second time against babashka.process from source

  Note that we launch our exec runner via `clojure.java.shell/sh` to avoid using `babashka.proccess`
  which is under test."
  (:require [babashka.fs :as fs]
            [babashka.process.test-utils :as u]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- find-exec-runner
  "Returns program arg vector for test runner.

  Depending on the test scenario, we'll either use our natively compiled `run_exec.clj`
  or we'll use `bb` to run `run_exec.clj` from source.
  See namespace docs for details."
  []
  (or (and (not= "bb" (System/getProperty "babashka.process.test.run-exec"))
           (some-> (fs/which (fs/file "./test-native/target" u/run-exec-exe))
                   fs/canonicalize
                   str
                   vector))
      (when-let [bb (u/*find-bb)]
        [(-> (fs/which bb) fs/canonicalize str)
         (->> ["process/test-native/src/babashka/test_native/run_exec.clj"
               "test-native/src/babashka/test_native/run_exec.clj"]
              (filter fs/exists?)
              first)])))

(defn print-env [f]
  (u/print-test-env)
  (println "- using exec runner:" (or (some-> (find-exec-runner) str) "<not found>"))
  (f))

(use-fixtures :once print-env)

(def ^:private exec-runner (find-exec-runner))

(defn- run-exec
  "Launch process that will exercise `babasha.process/exec`.

  See namespace docs for details.

  When running from bb we control version of babashka.process used via
  `babashka.process.test.reload` java system property.
  - if set, work from babashka.process sources
  - else we'll use the natively compiled built-in babasha.process

  To avoid dealing with any shell escaping rules, we spit `exec-args` to a file
  for consumption by `run_exec.clj`."
  [& exec-args]
  (fs/create-dirs "target")
  (let [exec-args-file (-> (fs/create-temp-file {:path "target" :prefix "bbp-run-exec" :suffix ".edn"})
                           fs/absolutize
                           str)
        reload-prop "babashka.process.test.reload"
        svm-opts (when-let [reload-val (System/getProperty reload-prop)]
                   [(format "-D%s=%s" reload-prop reload-val)])]
    (fs/delete-on-exit exec-args-file)
    (spit exec-args-file (pr-str exec-args))
    (-> (apply shell/sh (concat exec-runner svm-opts ["--file" exec-args-file]))
        (select-keys [:out :err :exit]))))

(deftest exec-replaces-runner-that-launches-it-test
  ;; runner prints ERROR:... and returns 42 if not replaced
  (when-let [bb (u/find-bb)]
    (is (= {:out (u/ols "hello\n")
            :err (u/ols "noprobs\n")
            :exit 100}
           (run-exec (format "%s %s :err noprobs :out hello :exit 100" bb u/wd))))))

(deftest exec-failure-to-launch-throws-an-exception-test
  ;; runner dumps serialized exeption on failure to launch
  (is (= "Path wontfindme does not point to executable file"
         (-> (run-exec "wontfindme")
             :out
             edn/read-string
             :cause))))

(when (fs/windows?)
  (deftest arg0-test-windows
    (when-let [bb (u/find-bb)]
      (testing "on Windows, arg0 is a no-op, make sure cmd still runs with it"
        (is (= {:out (u/ols "all good\n")
                :err ""
                :exit 0}
               (run-exec {:arg0 "newarg0"} (format "%s %s :out 'all good'" bb u/wd)))))) ))

(when (not (fs/windows?))
  (deftest arg0-test-mac-and-linux
    (when-let [bb (u/find-bb)]
      (testing "on macOS and Linux, arg0 is supported"
        (testing "baseline - not overriden"
          (is (= {:args (format "%s %s :ps-me" bb u/wd)}
                 (-> (run-exec  (format "%s %s :ps-me" bb u/wd))
                     :out
                     edn/read-string))))
        (is (= {:args (format "newarg0 %s :ps-me" u/wd)}
               (-> (run-exec {:arg0 "newarg0"} (format "%s %s :ps-me" bb u/wd))
                   :out
                   edn/read-string)))))))

(deftest exec-env-option-test
  (when-let [bb (u/find-bb)]
    (testing "env is inherited by default"
      (is (= (->> (System/getenv) (into {}))
             (-> (run-exec (format "%s %s :env" bb u/wd))
                 :out
                 edn/read-string))))
    (testing "request empty env"
      (let [vars (-> (run-exec {:env {}}
                               ;; add -cp '' so that bb does not require JAVA_HOME
                               (format "%s -cp '' %s :env" bb u/wd))
                     :out
                     edn/read-string)
            expected-vars (u/always-present-env-vars)]
        (is (= expected-vars (keys vars)))))
    (testing "add to existing env"
      (is (= (-> (into {} (System/getenv)) (assoc "FOO" "BAR"))
             (-> (run-exec {:extra-env {"FOO" "BAR"}}
                           (format "%s %s :env" bb u/wd))
                 :out
                 edn/read-string))))
    (testing "request a specific env"
      (let [vars (-> (run-exec {:env {"SOME_VAR" "SOME_VAL"
                                      :keyword_var "KWVARVAL"
                                      "keyword_val" :keyword-val}}
                               ;; add -cp '' so that bb does not require JAVA_HOME
                               (format "%s -cp '' %s :env" bb u/wd))
                     :out
                     edn/read-string)
            added-vars (apply dissoc vars (u/always-present-env-vars))]
        (is (= {"SOME_VAR" "SOME_VAL"
                "keyword_val" ":keyword-val"
                "keyword_var" "KWVARVAL"}
               added-vars))))))

(deftest pre-start-fn-test
  ;; shows that pre-start-fn is active and that executable is resolved
  (when-let [bb (u/find-bb)]
    (is (= {:out (u/ols (format "Pre-start-fn output {:cmd [%s %s :out foobar]}\nfoobar\n" (fs/which bb) u/wd))
            :err ""
            :exit 0 }
           ;; runner will always used a canned pre-start-fn, this should excercise the feature
           ;; this avoids introducing sci for our natively compiled runner
           (run-exec {:pre-start-fn :canned}
                     (format "%s %s :out foobar" bb u/wd))))))

(deftest resolves-program
  ;; use java -version instead of --version, it is supported even on jdk8
  (let [expected (shell/sh "java" "-version")]
    (is (zero? (:exit expected)) "sanity expected exit")
    (is (str/blank? (:out expected)) "sanity expected out" )
    (is (re-find #"(?i)jdk" (:err expected)) "sanity expected err")
    (testing "on-path"
      (is (= expected (run-exec "java -version"))))
    (testing "absolute"
      (is (= expected (run-exec (format "'%s' -version" (fs/canonicalize (fs/which "java")))))))
    (testing "relative"
      (let [java-dir (-> (fs/which "java") fs/parent fs/absolutize str)]
        (shell/with-sh-dir java-dir
          (= expected (run-exec "./java -version")))))))

(deftest cmd-opt-test
  (when-let [bb (u/find-bb)]
    (is (= {:exit 0
            :out (u/ols "o-one\no-two\n")
            :err (u/ols "e-one\n")}
           (run-exec {:cmd [bb u/wd ":out" "o-one" ":err" "e-one" ":out" "o-two"]
                      :out :string :err :string})))))
