(ns hooks.dollar
  (:require [clj-kondo.hooks-api :as api]))

(defn $ [{:keys [:node]}]
  (let [children (doall (keep (fn [child]
                                (let [s (api/sexpr child)]
                                  (when (and (seq? s)
                                             (= 'unquote (first s)))
                                    (first (:children child)))))
                              (:children node)))]
    {:node (assoc node :children children)}))
