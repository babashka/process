(ns babashka.process.pprint
  (:require [clojure.pprint :as pprint :only [pprint simple-dispatch]]))

(defmethod pprint/simple-dispatch babashka.process.Process [proc]
  (pprint/pprint (into {} proc)))
