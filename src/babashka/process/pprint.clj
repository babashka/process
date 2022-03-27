(ns babashka.process.pprint
  (:require [babashka.process]
            [clojure.pprint :as pprint]))

(defmethod pprint/simple-dispatch babashka.process.Process [proc]
  (pprint/pprint (into {} proc)))
