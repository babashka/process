(ns babashka.process
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.io InputStream]))


(defn process
  ([args] (process args nil))
  ([args {:keys [:err
                 :in :in-enc
                 :out :out-enc
                 :timeout
                 :throw]
          :or {out :string
               err :string
               throw true}}]
   (let [args (mapv str args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              (identical? err  :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out  :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in   :inherit) (.redirectInput ProcessBuilder$Redirect/INHERIT))
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
     (let [future? (or (identical? out :stream)
                       (identical? err :stream)
                       timeout)
           exit (if future?
                  (future (.waitFor proc))
                  (.waitFor proc))
           exit (if timeout
                  (deref exit timeout nil)
                  exit)
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
       (if (and throw
                (not future?)
                (string? err)
                (not (zero? exit)))
         (throw (ex-info err res))
         res)))))

;;;; Examples

(comment
  ;; slurp output stream
  (-> (process ["ls"]) :out slurp)
  ;; return output as string
  (-> (process ["ls"] {:out :string}) :out)
  ;; redirect output to stdout
  (do (-> (process ["ls"] {:out :inherit})) nil)
  ;; redirect output from one process to input of another process
  (let [is (-> (process ["ls"]) :out)]
    (process ["cat"] {:in is
                      :out :inherit})
    nil)
  )
