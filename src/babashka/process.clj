(ns babashka.process
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder$Redirect]))

(defn- add-env
  [process-builder environment]
  (if environment
    (let [process-environment(.environment process-builder)]
      (doseq [[k v] environment]
        (.put process-environment k v))
      process-builder)
    process-builder))

(defn process
  ([args] (process args nil))
  ([args opts] (if (map? args)
                 (process args opts nil)
                 (process nil args opts)))
  ([prev args {:keys [:err
                      :in :in-enc
                      :out :out-enc
                      :dir
                      :env
                      :timeout
                      :throw
                      :wait]
               :or {out :string
                    err :string
                    dir (System/getProperty "user.dir")
                    throw true
                    wait true}}]
   (let [in (or in (:out prev))
         args (mapv str args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              dir (.directory (io/file dir))
              env (add-env env)
              (identical? err :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in  :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)]
     (when (string? in)
       (with-open [w (io/writer (.getOutputStream proc))]
         (binding [*out* w]
           (print in)
           (flush))))
     (when-not (keyword? in)
       (future
         (with-open [os (.getOutputStream proc)]
           (io/copy in os :encoding in-enc))))
     (let [exit (if wait
                  (.waitFor proc)
                  (future (.waitFor proc)))
           _ (when timeout (deref exit timeout ::timed-out))
           res {:proc proc
                :exit exit}
           res (if (identical? out :string)
                 (assoc res :out (slurp (.getInputStream proc)))
                 (assoc res :out (.getInputStream proc)))
           err (if (identical? err :string)
                 (slurp (.getErrorStream proc))
                 (.getErrorStream proc))
           res (assoc res :err err)]
       (when-not (keyword? out)
         (io/copy (.getInputStream proc) out :encoding out-enc))
       (if throw
         (if (identical? exit ::timed-out)
           (throw (ex-info "Timeout." res))
           (if (and
                (string? err)
                exit
                (number? exit)
                (not (zero? exit)))
             (throw (ex-info err res))
             res))
         res)))))

;;;; Examples

(comment
  ;; slurp output stream
  (-> (process ["ls"]) :out)
  ;;=> "LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"

  (-> (process ["ls"] {:dir "test/babashka"}) :out)
  ;;=> "process_test.clj\n"

  (-> (process ["ls"] {:dir "src/babashka"}) :out)
  ;;=> "process.clj\n"

  ;; return output as string
  (-> (process ["ls"] {:out :string}) :out)

  ;; redirect output to stdout
  (do (-> (process ["ls"] {:out :inherit})) nil)

  ;; if we don't define this
  (-> (process ["/bin/bash" "-c" "echo $INVALID_ENV_VALUE"]) :out)
  ;;=> "\n"

  (-> (process ["/bin/bash" "-c" "echo $GREETING"] {:env {"GREETING" "Hello"}}) :out)
  ;;=> "Hello\n"

  ;; redirect output from one process to input of another process
  (let [is (-> (process ["ls"]) :out)]
    (process ["cat"] {:in is
                      :out :inherit})
    nil)

  )
