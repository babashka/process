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
      (is (zero? exit)))))
