(ns quickdoc
  (:require [pod.borkdude.clj-kondo :as clj-kondo]
            [clojure.string :as str]))

(def var-defs
  (-> (clj-kondo/run! {:lint ["src"]
                       :config {:output {:analysis true}}})
      :analysis :var-definitions))

(def nss (group-by :ns var-defs))

(def docs
  (with-out-str
    (doseq [[ns ana] nss
            :let [_ (println "##" ns)]
            var (sort-by :name ana)]
      (println "###" (format "`%s`" (:name var)))
      (when-let [doc (:doc var)]
        (println doc)))))

(spit "README.md"
      (str/replace (slurp "README.md.template")
                   "{{ quickdoc }}" docs))
