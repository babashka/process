(ns babashka.process-test
  (:require [babashka.process :refer [tokenize process check sh $ pb start] :as p]
            [clojure.java.io :as io]
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
         (tokenize "echo '{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}'"))))

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
               (process ["grep" "README"]) :out slurp))))
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
      (testing "$ macro"
        (is (= "{:a 1}\n" (-> ($ echo ~config) :out slurp)))
        (let [sw (java.io.StringWriter.)]
          (is (= "{:a 1}\n" (do (-> ^{:out sw}
                                    ($ echo ~config)
                                    ($ cat)
                                    deref)
                                (str sw))))))))
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
                     :out)))))

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
