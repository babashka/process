(ns babashka.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :refer [tokenize process check sh $ pb start] :as p]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

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
         (tokenize "\"\\foo\""))))

(deftest process-test
  (testing "By default process returns string out and err, returning the exit
  code in a delay. Waiting for the process to end happens through realizing the
  delay. Waiting also happens implicitly by not specifying :stream, since
  realizing :out or :err needs the underlying process to finish."
    (let [res (process ["ls"])
          out (slurp (:out res))
          err (slurp (:err res))
          checked (check res) ;; check should return process with :exit code
          ;; populated
          exit (:exit checked)]
      (is (string? out))
      (is (string? err))
      (is (not (str/blank? out)))
      (is (str/blank? err))
      (is (number? exit))
      (is (zero? exit))))
  (testing "When specifying :out and :err both a non-strings, the process keeps
  running. :in is the stdin of the process to which we can write. Calling close
  on that stream closes stdin, so a program like cat will exit. We wait for the
  process to exit by realizing the exit delay."
    (let [res (process '[cat] {:err :inherit})
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
      (is (= "hello\n" (slurp out-stream)))))
  (testing "copy input from string"
    (let [proc (process '[cat] {:in "foo"})
          out (:out proc)
          ret (:exit @proc)]
      (is (= 0 ret))
      (is (= "foo" (slurp out)))))
  (testing "copy output to *out*"
    (let [s (with-out-str
              @(process '[cat] {:in "foo" :out *out*}))]
      (is (= "foo" s))))
  (testing "copy stderr to *out*"
    (let [s (with-out-str
              (-> (process '[curl "foo"] {:err *out*})
                  deref :exit))]
      (is (pos? (count s)))))
  (testing "chaining"
    (is (= "README.md\n"
           (-> (process ["ls"])
               (process ["grep" "README"]) :out slurp)))
    (is (= "README.md\n"
           (-> (sh ["ls"])
               (sh ["grep" "README"]) :out))))
  (testing "use of :dir options"
    (is (= (-> (process ["ls"]) :out slurp)
           (-> (process ["ls"] {:dir "."}) :out slurp)))
    ;; skip this test when ran from babashka lib tests
    (when (.exists (io/file "src" "babashka" "process.clj"))
      (is (= (-> (process ["ls"] {:dir "test/babashka"}) :out slurp)
             "process_test.clj\n")))
    (is (not= (-> (process ["ls"]) :out slurp)
              (-> (process ["ls"] {:dir "test/babashka"}) :out slurp))))
  (testing "use of :env options"
    (is (= "" (-> (process ["env"] {:env {}}) :out slurp)))
    (let [out (-> (sh "env" {:extra-env {:FOO "BAR"}}) :out)]
      (is (str/includes? out "PATH"))
      (is (str/includes? out "FOO=BAR")))
    (is (= ["SOME_VAR=SOME_VAL"
            "keyword_val=:keyword-val"
            "keyword_var=KWVARVAL"]
           (-> (process ["env"] {:env {"SOME_VAR" "SOME_VAL"
                                       :keyword_var "KWVARVAL"
                                       "keyword_val" :keyword-val}})
               :out
               slurp
               (str/split-lines)
               (sort)))))
  (testing "check throws on non-zero exit"
    (let [err-form '(binding [*out* *err*]
                      (println "error123")
                      (System/exit 1))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"error123"
           (-> (process ["clojure" "-e" (str err-form)]) (check)))
          "with :err string"))
    (is (thrown?
         clojure.lang.ExceptionInfo #"failed"
         (-> (process ["clojure" "-e" (str '(System/exit 1))])
             (check)))
        "With no :err string")
    (testing "and the exception"
      (let [command ["clojure" "-e" (str '(System/exit 1))]]
        (try
          (-> (process command)
              (check))
          (catch clojure.lang.ExceptionInfo e
            (testing "contains the process arguments"
              (is (= command (:cmd (ex-data e)))))
            (testing "and contains a babashka process type"
              (is (= :babashka.process/error (:type (ex-data e))))))))))
  (testing "$ macro"
    (let [config {:a 1}]
      (is (= "{:a 1}\n" (-> ($ echo ~config) :out slurp)))
      (let [sw (java.io.StringWriter.)]
        (is (= "{:a 1}\n" (do (-> ^{:out sw}
                                  ($ echo ~config)
                                  deref)
                              (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= "{:a 1}\n" (do (-> ($ ~{:out sw} echo ~config)
                                  deref)
                              (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= "{:a 1}\n" (do (-> ($ {:out sw} echo ~config)
                                  deref)
                              (str sw)))))))
  (testing "pb + start = process"
    (let [out (-> (process ["ls"]) :out slurp)]
      (is (and (string? out) (not (str/blank? out))))
      (is (= out (-> (pb ["ls"]) (start) :out slurp)))))
  (testing "output to string"
    (is (string? (-> (process ["ls"] {:out :string})
                     check
                     :out))))
  (testing "tokenize"
    (is (string? (-> (process "ls -la" {:out :string})
                     check
                     :out)))
    (is (string? (-> ^{:out :string} ($ "ls -la" )
                     check
                     :out)))
    (is (string? (-> (sh "ls -la")
                     :out))))
  #?(:bb nil
     :clj
     (testing "deref timeout"
       (is (= ::timeout (deref (process ["clojure" "-e" "(Thread/sleep 500)"]) 250 ::timeout)))
       (is (= 0 (:exit (deref (process ["ls"]) 250 nil)))))))

(deftest dollar-pipe-test
  (is (str/includes?
       (-> ($ ls -la)
           ($ {:out :string} cat) deref :out)
       "total"))
  (is (str/includes?
       (-> ($ ls -la)
           ^{:out :string} ($ cat) deref :out)
       "total"))
  (is (= "hello\nhello\n"
         (-> ($ echo hello) ($ sed p) deref :out slurp))))

(deftest redirect-file-test
  (fs/with-temp-dir [tmp {}]
    (let [out (fs/file tmp "out.txt")]
      @(p/process "echo hello" {:out :write
                                :out-file out})
      (is (str/starts-with? (slurp out) "hello"))
      @(p/process "echo hello" {:out :append
                                :out-file out})
      (is (= 2 (count (re-seq #"hello" (slurp out)))))))
  (fs/with-temp-dir [tmp {}]
    (let [out (fs/file tmp "err.txt")]
      @(p/process "bash -c '>&2 echo \"error\"'"
                  {:err :write
                   :err-file out})
      (is (str/starts-with? (slurp out) "error"))
      @(p/process "bash -c '>&2 echo \"error\"'"
                  {:err :append
                   :err-file out})
      (is (= 2 (count (re-seq #"error" (slurp out))))))))

(deftest pprint-test
  (testing "calling pprint on a process without requiring pprint namespace causes exception (ambiguous on pprint/simple-dispatch multimethod)"
    (is (thrown-with-msg? IllegalArgumentException #"Multiple methods in multimethod 'simple-dispatch' match dispatch value"
          (-> (process "cat missing-file.txt") pprint))))
  (testing "after requiring pprint namespace, process gets pprinted as a map"
    (do
      (require '[babashka.process.pprint])
      (is (str/includes? (with-out-str (-> (process "cat missing-file.txt") pprint)) ":proc")))))

(defmacro ^:private jdk9+ []
  (if (identical? ::ex
                  (try (import 'java.lang.ProcessHandle)
                       (catch Exception _ ::ex)))
    '(do
       (require '[babashka.process :refer [pipeline]])
       (deftest pipeline-test
         (testing "pipeline returns processes nested with ->"
           (is (= [["ls"] ["cat"]] (map :cmd (pipeline (-> (process ["ls"]) (process ["cat"])))))))))
    '(do
       (require '[babashka.process :refer [pipeline pb]])
       (deftest inherit-test
         (let [proc (process "echo" {:shutdown p/destroy-tree
                                     :inherit true})
               null-input-stream-class (class (:out proc))
               null-output-stream-class (class (:in proc))]
           (is (= null-input-stream-class (class (:err proc))))
           (let [x (process ["cat"] {:shutdown p/destroy-tree
                                     :inherit true
                                     :in "foo"})]
             (is (not= null-output-stream-class (class (:in x))))
             (is (= null-input-stream-class (class (:out x))))
             (is (= null-input-stream-class (class (:err x)))))
           (let [x (process ["cat"] {:shutdown p/destroy-tree
                                     :inherit true
                                     :out "foo"})]
             (is (= null-output-stream-class (class (:in x))))
             (is (not= null-input-stream-class (class (:out x))))
             (is (= null-input-stream-class (class (:err x)))))
           (let [x (process ["cat"] {:shutdown p/destroy-tree
                                     :inherit true
                                     :err "foo"})]
             (is (= null-output-stream-class (class (:in x))))
             (is (= null-input-stream-class (class (:out x))))
             (is (not= null-input-stream-class (class (:err x)))))))
       (deftest pipeline-test
         (testing "pipeline returns processes nested with ->"
           (is (= [["ls"] ["cat"]] (map :cmd (pipeline (-> (process ["ls"]) (process ["cat"])))))))
         (testing "pipeline returns processes created with pb"
           (is (= [["ls"] ["cat"]] (map :cmd (pipeline (pb ["ls"]) (pb ["cat"]))))))
         (testing "pbs can be chained with ->"
           (let [chain (-> (pb ["ls"]) (pb ["cat"] {:out :string}) start deref)]
             (is (string? (slurp (:out chain))))
             (is (= [["ls"] ["cat"]] (map :cmd (pipeline chain))))))))))

(jdk9+)

;;;; Windows tests
;;;; Run with clojure -M:test -i windows

(defmacro when-windows [& body]
  (when (str/starts-with? (System/getProperty "os.name") "Win")
    `(do ~@body)))

(when-windows
    (deftest ^:windows windows-executable-resolver-test
      (when (some-> (resolve 'p/windows?) deref)
        (prn (-> @(p/process "java --version" {:out :string})
                 :out))
        (prn (-> @(p/process ["java" "--version"] {:out :string})
                 :out)))))

(when-windows
    (deftest ^:windows windows-invoke-git-with-space-test
      (let [proc @(p/process ["git " "status"] {:out :string})]
        (is (string? (:out proc)))
        (is (zero? (:exit proc))))))

(when-windows
  (deftest ^:windows windows-pprint-test
    (testing "calling pprint on a process without requiring pprint namespace causes exception (ambiguous on pprint/simple-dispatch multimethod)"
      (is (thrown-with-msg? IllegalArgumentException #"Multiple methods in multimethod 'simple-dispatch' match dispatch value"
            (-> (process "cmd /c type missing-file.txt") pprint))))
    (testing "after requiring pprint namespace, process gets pprinted as a map"
      (do
        (require '[babashka.process.pprint])
        (is (str/includes? (with-out-str (-> (process "cmd /c type missing-file.txt") pprint)) ":proc"))))))
