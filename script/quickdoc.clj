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
            var (sort-by :name ana)]
      (println "###" (format "`%s`" (:name var)))
      ;; (.println System/err (keys var))
      (when-let [arg-lists (seq (:arglist-strs var))]
        (doseq [arglist arg-lists]
          (println (format "<code>(%s %s)</code><br>" (:name var) arglist))))
      (when-let [doc (:doc var)]
        (println doc)))))

(spit "README.md"
      (str/replace (slurp "README.template.md")
                   "{{ quickdoc }}" docs))
