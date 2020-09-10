(ns babashka.process-test
  (:require [babashka.process :refer [process]]
            [clojure.test :as t :refer [deftest is testing]]
            [clojure.string :as str]))

(deftest process-test
  (testing "by default process returns string out and err and waits for the
  process to finish, returning the exit code as a number"
    (let [res (process ["ls"])
          out (:out res)
          err (:err res)
          exit (:exit res)]
      (is (string? out))
      (is (string? err))
      (is (not (str/blank? out)))
      (is (str/blank? err))
      (is (number? exit))
      (is (zero? exit))))
  (testing "copy input from string and copy to *out*"
    (let [s (with-out-str
              (process ["cat"] {:in "foo" :out *out*}))]
      (is (= "foo" s))))
  (testing "chaining"
    (is (= "README.md\n"
           (-> (process ["ls"])
               (process ["grep" "README"]) :out))))
  (testing "use of :dir options"
    (is (= (-> (process ["ls"]) :out)
           (-> (process ["ls"] {:dir "."}) :out)))
    (is (= (-> (process ["ls"] {:dir "test/babashka"}) :out)
           "process_test.clj\n"))
    (is (not= (-> (process ["ls"]) :out)
              (-> (process ["ls"] {:dir "test/babashka"}) :out))))
  (testing "use of :env options"
    (is (= "" (:out (process ["env"] {:env {}}))))
    (is (= ["SOME_VAR=SOME_VAL"
            "keyword_val=:keyword-val"
            "keyword_var=KWVARVAL"]
           (-> (process ["env"] {:env {"SOME_VAR" "SOME_VAL"
                                       :keyword_var "KWVARVAL"
                                       "keyword_val" :keyword-val}})
               :out
               (str/split-lines)
               (sort)))))

  (testing "with :throw, throws on non-zero exit"
    (let [err-form '(binding [*out* *err*]
                      (println "error123")
                      (System/exit 1))]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"error123"
            (process ["clojure" "-e" (str err-form)] {:throw true}))
          "with :err string"))
    (is (thrown?
          clojure.lang.ExceptionInfo #"failed"
          (process ["clojure" "-e" (str '(System/exit 1))] {:throw true :err :inherit}))
        "With no :err string")
    (testing "and the exception contains the process arguments"
      (let [args ["clojure" "-e" (str '(System/exit 1))]]
        (try
          (process args {:throw true :err :inherit})
          (catch clojure.lang.ExceptionInfo e
            (is (= args (:args (ex-data e))))))))))
