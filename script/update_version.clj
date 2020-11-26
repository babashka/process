(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn next-version [version]
  (if (str/ends-with? version "-SNAPSHOT")
    (str/replace version "-SNAPSHOT" "")
    (let [[major minor patch] (str/split version #"\.")
          patch (Integer. patch)
          patch (inc patch)]
      (str (str/join "." [major minor patch])
           "-SNAPSHOT"))))

(def server-file (io/file "src" "babashka" "nrepl" "impl" "server.clj"))
(def server-code (slurp server-file))
(def new-server-code
  (str/replace server-code #"(\(def babashka-nrepl-version \")(.*)(\"\))"
               (fn [[_ open version close]]
                 (str open (next-version version) close))))
(spit server-file new-server-code)

(def project-file (io/file "project.clj"))
(def project-code (slurp project-file))
(def new-project-code
  (str/replace project-code #"(babashka/babashka.nrepl \")(.*)(\")"
               (fn [[_ open version close]]
                 (str open (next-version version) close))))
(spit project-file new-project-code)
