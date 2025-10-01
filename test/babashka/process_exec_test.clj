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
    (let [{:keys [out] :as res} (-> (apply shell/sh (concat exec-runner svm-opts ["--file" exec-args-file]))
                  (select-keys [:out :err :exit]))]
      (if (str/includes? out ":bbp-test-run-exec-exception")
        (let [ex-map (-> (edn/read-string out) :bbp-test-run-exec-exception)
              msg (str "runexec-relayed: " (:cause ex-map))]
          (throw (ex-info msg {:type :bbp-test-run-exec-exception
                               :res res})))
        res))))

(deftest exec-replaces-runner-that-launches-it-test
  ;; runner prints ERROR:... and returns 42 if not replaced
  (when-let [bb (u/find-bb)]
    (is (= {:out (u/ols "hello\n")
            :err (u/ols "noprobs\n")
            :exit 100}
           (run-exec (format "%s %s :err noprobs :out hello :exit 100" bb u/wd))))))

(when (fs/windows?)
  (deftest arg0-windows-test
    (when-let [bb (u/find-bb)]
      (testing "on Windows, arg0 is a no-op, make sure cmd still runs with it"
        (is (= {:out (u/ols "all good\n")
                :err ""
                :exit 0}
               (run-exec {:arg0 "newarg0"} (format "%s %s :out 'all good'" bb u/wd)))))) ))

(when (not (fs/windows?))
  (deftest arg0-mac-and-linux-test
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
        (is (empty? (remove expected-vars (keys vars))))))
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

(defn elines
  "Convenience fn to return stdout from execed `program` as vector of lines.
  (Exec does not support `:dir`)"
  [program]
  (-> (run-exec {:out :string}
                program)
      :out
      str/split-lines))

(when (not (fs/windows?))
  ;; see also babashka.process-test/process-resolve-program-macos-linux-test
  (deftest resolve-program-macos-linux-test
    (u/with-program-scenario {:cwd     [:sh]
                              :workdir [:sh]
                              :on-path [:sh]}
      (doseq [[program expected-exedir]
              [[(u/test-program :sh)               :on-path]
               [(str "./" (u/test-program :sh))    :cwd]
               [(u/test-program-abs :workdir :sh)  :workdir]]
              :let [desc (format "program: %s expected-exedir %s" program expected-exedir)]]
        (is (= (u/etpo {:exedir expected-exedir
                        :exename (u/test-program :sh)
                        :workdir :cwd})
               (elines program))
            desc))
      (u/with-program-scenario {:cwd     [:sh]
                                :workdir [:sh]}
        (is (thrown-with-msg? Exception #"runexec-relayed: Cannot resolve"
                              (elines (u/test-program :sh))))))))

(when (fs/windows?)
  ;; see also babashka.process-test/process-resolve-program-win-test
  (deftest resolve-program-win-test
    (testing "program `a` resolves from PATH but not cwd"
      (doseq [[expected-ext on-path-scenario]
              [[:com [:bat :cmd :com :exe :ps1]]
               [:exe [:bat :cmd :exe :ps1]]
               [:bat [:bat :cmd :ps1]]
               [:cmd [:cmd :ps1]]]
              :let [desc (format "expected-ext: %s on-path: %s" expected-ext on-path-scenario)]]
        (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                  :on-path on-path-scenario}
          (is (= (u/etpo {:exedir :on-path
                          :exename (u/test-program expected-ext)
                          :workdir :cwd})
                 (elines (u/test-program)))
              desc)))
      (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                :on-path [:ps1]}
        (is (thrown-with-msg? Exception #"runexec-relayed: Cannot resolve"
                              (elines (u/test-program))))))
    (testing "program `a.<ext>` resolves from PATH but not cwd nor workdir"
      (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                :workdir [:bat :cmd :com :exe :ps1]
                                :on-path [:bat :cmd :com :exe :ps1]}
        (doseq [ext [:bat :cmd :com :exe]
                :let [program (u/test-program ext)
                      desc (format "program: %s" program)]]
          (is (= (u/etpo {:exedir :on-path
                          :exename program
                          :workdir :cwd})
                 (elines program))
              desc)))
      (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                :workdir [:bat :cmd :com :exe :ps1]}
        (doseq [ext [:bat :cmd :com :exe]
                :let [program (u/test-program ext)
                      desc (format "program: %s" program)]]
          (is (thrown-with-msg? Exception #"runexec-relayed: Cannot resolve"
                                (elines program))
              desc))))
    (testing "program `.\\a` resolves from cwd and workdir"
      (doseq [[expected-ext scenario-exts]
              [[:com [:bat :cmd :com :exe :ps1]]
               [:exe [:bat :cmd :exe :ps1]]
               [:bat [:bat :cmd :ps1]]
               [:cmd [:cmd :ps1]]]
              :let [desc (format "expected-ext: %s scenario-exts: %s" expected-ext scenario-exts)]]
        (u/with-program-scenario {:cwd     scenario-exts
                                  :workdir scenario-exts
                                  :on-path [:bat :cmd :com :exe :ps1]}
          (is (= (u/etpo {:exedir  :cwd
                          :exename (u/test-program expected-ext)
                          :workdir :cwd})
                 (elines (str ".\\" (u/test-program))))
              desc)))
      (u/with-program-scenario {:cwd     [:ps1]
                                :workdir [:ps1]
                                :on-path [:bat :cmd :com :exe :ps1]}
        (is (thrown-with-msg? Exception #"runexec-relayed: Cannot resolve"
                              (elines (str ".\\" (u/test-program)))))))
    (testing "program `.\\a.<ext>` resolves from cwd and workdir"
      (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                :workdir [:bat :cmd :com :exe :ps1]
                                :on-path [:bat :cmd :com :exe :ps1]}
        (doseq [ext [:bat :cmd :com :exe]
                :let [program (str ".\\" (u/test-program ext))
                      desc (format "program: %s" program)]]
          (is (= (u/etpo {:exedir :cwd
                          :exename program
                          :workdir :cwd})
                 (elines program))
              desc))))
    (testing "program absolute path of `a.<ext>` resolves"
      (u/with-program-scenario {:cwd     [:bat :cmd :com :exe :ps1]
                                :workdir [:bat :cmd :com :exe :ps1]
                                :on-path [:bat :cmd :com :exe :ps1]}
        (doseq [path [:cwd :workdir :on-path]
                ext [:bat :cmd :com :exe]
                :let [program (-> (fs/file (u/real-dir path) (u/test-program ext))
                                  fs/canonicalize str)
                      desc (format "program: %s" program)]]
          (is (= (u/etpo {:exedir path
                          :exename (u/test-program ext)
                          :workdir :cwd})
                 (elines program))
              desc))))
    (testing "program absolute path of `a` resolves"
      (doseq [path [:cwd :workdir :on-path]
              [expected-ext scenario-exts] [[:com [:bat :cmd :com :exe :ps1]]
                                            [:exe [:bat :cmd :exe :ps1]]
                                            [:bat [:bat :cmd :ps1]]
                                            [:cmd [:cmd :ps1]]]
              :let [program (-> (fs/file (u/real-dir path) (u/test-program))
                                fs/canonicalize str)
                    desc (format "program: %s expected-ext: %s" program expected-ext)]]
        (u/with-program-scenario (-> {:cwd     [:bat :cmd :com :exe :ps1]
                                      :workdir [:bat :cmd :com :exe :ps1]
                                      :on-path [:bat :cmd :com :exe :ps1]}
                                     (assoc path scenario-exts))
          (is (= (u/etpo {:exedir path
                          :exename (u/test-program expected-ext)
                          :workdir :cwd})
                 (elines program))
              desc)))
      (u/with-program-scenario {:cwd [:ps1]
                                :workdir [:ps1]
                                :on-path [:ps1]}
        (doseq [path [:cwd :workdir :on-path]
                :let [program (-> (fs/file (u/real-dir path) (u/test-program))
                                  fs/canonicalize str)
                      desc (format "program: %s" program)]]
          (is (thrown-with-msg? Exception #"runexec-relayed: Cannot resolve"
                                (elines program))
              desc))))
    (testing "can launch `.ps1` script through powershell"
      (u/with-program-scenario {:cwd [:ps1]
                                :workdir [:ps1]}
        (is (= (u/etpo {:exedir :cwd
                        :exename (u/test-program :ps1)
                        :workdir :cwd})
               (-> (run-exec {:out :string}
                            "powershell.exe -File" (str ".\\" (u/test-program :ps1)))
                   :out
                   str/split-lines)))))))

(deftest cmd-opt-test
  (when-let [bb (u/find-bb)]
    (is (= {:exit 0
            :out (u/ols "o-one\no-two\n")
            :err (u/ols "e-one\n")}
           (run-exec {:cmd [bb u/wd ":out" "o-one" ":err" "e-one" ":out" "o-two"]
                      :out :string :err :string})))))
