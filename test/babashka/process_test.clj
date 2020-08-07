(ns babashka.process-test
  (:require [babashka.process :refer [process]]
            [clojure.test :as t :refer [deftest is]]
            [clojure.string :as str]))

(deftest process-test
  (let [res (process ["ls"])
        out (:out res)
        err (:err res)]
    (is (string? out))
    (is (string? err))
    (is (not (str/blank? out)))
    (is (str/blank? err))))


