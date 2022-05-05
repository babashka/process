(ns quickdoc
  (:require [pod.borkdude.clj-kondo :as clj-kondo]
            [clojure.string :as str]))

(def var-defs
  (-> (clj-kondo/run! {:lint ["src"]
                       :config {:output {:analysis {:arglists true}}}})
      :analysis :var-definitions))

(def nss (group-by :ns var-defs))

(def docs
  (with-out-str
    (doseq [[ns ana] nss
            :let [_ (println "##" ns)]
            var (sort-by :name ana)
            :when (and (not (:no-doc var))
                       (seq (:arglists-strs var)))]
      (println "###" (format "`%s`" (:name var)))
      ;; (.println System/err (keys var))
      (when-let [arg-lists (seq (:arglist-strs var))]
        (doseq [arglist arg-lists]
          (println (format "<code>%s</code><br>"  arglist))))
      (when-let [doc (:doc var)]
        (println)
        (println doc)
        ))))

(spit "README.md"
      (str/replace (slurp "README.template.md")
                   "{{ quickdoc }}" docs))
