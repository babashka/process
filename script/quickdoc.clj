(ns quickdoc
  (:require [clojure.string :as str]
            [pod.borkdude.clj-kondo :as clj-kondo]))

(defn quickdoc [{:keys [branch template outfile
                        github/repo]
                 :or {branch "main"
                      template "README.template.md"
                      outfile "README.md"}}]
  (let [var-defs
        (-> (clj-kondo/run! {:lint ["src"]
                             :config {:output {:analysis {:arglists true
                                                          :var-definitions {:meta [:no-doc]}}}}})
            :analysis :var-definitions)

        nss (group-by :ns var-defs)
        docs
        (with-out-str
          (doseq [[ns ana] nss
                  :let [_ (println "##" ns)]
                  var (sort-by :name ana)
                  :when (and (not (:no-doc (:meta var)))
                             (not (:private var))
                             (not (= 'clojure.core/defrecord (:defined-by var))))]
            ;; (.println System/err (:defined-by var))
            (println "###" (format "`%s`" (:name var)))
            ;; (.println System/err (keys var))
            (when-let [arg-lists (seq (:arglist-strs var))]
              (doseq [arglist arg-lists]
                (println (format "<code>%s</code><br>"  arglist))))
            (when-let [doc (:doc var)]
              (println)
              (println doc)
              )
            (println)
            (println
             (format
              "[Source](%s/blob/%s/%s#L%s-L%s)"
              repo
              branch
              (:filename var)
              (:row var)
              (:end-row var)))))]
    (spit outfile
          (str/replace (slurp template)
                       "{{ quickdoc }}" docs))))

(quickdoc {:branch "master"
           :github/repo "https://github.com/babashka/process"
           })
