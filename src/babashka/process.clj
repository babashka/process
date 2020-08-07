(ns babashka.process
  (:require [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.io InputStream]))


(defn process
  ([args] (process args nil))
  ([args {:keys [:err
                 :out
                 :in :in-enc]}]
   (let [args (mapv str args)
         pb (cond-> (ProcessBuilder. ^java.util.List args)
              (identical? err  :inherit) (.redirectError ProcessBuilder$Redirect/INHERIT)
              (identical? out :inherit) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (identical? in :inherit)  (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)]
     (when (string? in)
       (with-open [w (io/writer (.getOutputStream proc))]
         (binding [*out* w]
           (print in)
           (flush))))
     (when (instance? InputStream in)
       (future
         (with-open [os (.getOutputStream proc)]
           (io/copy in os :encoding in-enc))))
     (let [exit (future (.waitFor proc))
           res {:proc proc
                :exit exit}]
       (if (identical? out :string)
         (assoc res :out (let [sw (java.io.StringWriter.)]
                              (with-open [w (io/reader (.getInputStream proc))]
                                (io/copy w sw))
                              (str sw)))
         (assoc res :out (.getInputStream proc)))))))

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
