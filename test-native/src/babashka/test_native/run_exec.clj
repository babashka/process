;; a wee script to test exec
;; exec can only run when natively compiled by GraalVM so this script must be run from bb
;; or from a custom natively compile app
(ns babashka.test-native.run-exec
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk])
  (:gen-class))

;; When running from bb, if we wish to use babashka.process from sources
;; rather than the babashka.process that has been compiled into the bb
;; binary, we must reload the babasha.process namespace
(if (and (System/getProperty "babashka.version") ;; running from bb?
         (System/getProperty "babashka.process.test.reload")) ;; set by our tests
  (require '[babashka.process :as p] :reload)
  (require '[babashka.process :as p]))

(set! *warn-on-reflection* true)

(defn- load-exec-args
  "Load args for exec from edn.
  To keep things simple, we'll replace any specified :pre-start-fn with a canned one. "
  [args]
  (->> args
       edn/read-string
       (walk/postwalk (fn [n]
                        (if (:pre-start-fn n)
                          (assoc n :pre-start-fn (fn [m] (println "Pre-start-fn output" m)))
                          n)))))

(defn- parse-args [args]
  (cond
    (= 1 (count args))
    (load-exec-args (first args))

    (and (= 2 (count args))
         (or (= "--file" (first args))
             (= "-f" (first args))))
    (-> (second args) slurp load-exec-args)

    :else
    (throw (ex-info "Invalid usage, specify '(some list of args for exec)' or --file args-in.edn" {}))))


(defn -main
  "call with a list of `args` for `babashka.process/exec`

  for adhoc (under bash, Windows shell will have different escaping rules):
  bb exec-run.clj \"({:arg0 'new-arg0'} bb wd.clj :out foo :exit 3)\"

  to avoid shell command escaping hell can also read args from edn file
  bb exec-run.clj --file some/path/here.edn"
  [& args]
  (let [exec-args (parse-args args)]
    (try
      #_{:clj-kondo/ignore [:unresolved-namespace]}
      (apply p/exec exec-args)
      ;; we should never reach this line
      (println "ERROR: exec did not replace process.")
      (System/exit 42)
      (catch Exception ex
        ;; an error occurred launching exec
        (println (pr-str (Throwable->map ex)))))))

;; support invocation from babashka when run as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
