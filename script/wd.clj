;; wd - wee dummy - an os-agnostic bb script launched by our unit tests
(require '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str] )

;; usage
;; :out <somestring> - dump <somestring> to stdout
;; :err <somestring> - dump <somestring> to stderr
;; :ls <somefile> - dir of <somefile> to stdout
;; :env - dump env to stdout
;; :grep <somestring> - returns all lines from stdin matching <somestring>
;; :upper - read and emit lines from stdin, but converted to uppercase
;; :ps-me - dump some info for current process (macOS & Linux only)
;; :sleep <ms> - sleep for <ms>
;; :exit <someval> - exits with <someval>

;; the naivest of cmd line parsing
(doseq [[cmd val] (partition-all 2 1 *command-line-args*)]
  (case cmd
    ":out" (println val)
    ":err" (binding [*out* *err*] (println val))
    ":ls" (pr (->> val io/file (.listFiles) (map str) sort))
    ":env" (pr (->> (System/getenv) (into {})))
    ":grep" (doseq [l (->> *in* io/reader line-seq (filter #(str/includes? % val)))]
              (println l))
    ":upper" (doseq [l (->> *in* io/reader line-seq)]
               (println (str/upper-case l)))
    ;; macos and linux only
    ":ps-me" (let [pid (.pid (java.lang.ProcessHandle/current))]
               (pr {:args (-> (shell/sh "ps" "-o" "args=" (str pid)) :out str/trim)}))
    ":sleep" (Thread/sleep (parse-long val))
    ":exit" (System/exit (parse-long val))
    nil))
