(defproject babashka/process "0.4.13"
  :description "Clojure library for shelling out / spawning subprocesses"
  :url "https://github.com/babashka/process"
  :scm {:name "git"
        :url "https://github.com/babashka/process"}
  :license {:name "EPL-1.0"
            :url "https://www.eclipse.org/legal/epl-1.0/"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [babashka/fs "0.2.12"]]
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])
