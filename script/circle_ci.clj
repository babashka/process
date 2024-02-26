(ns circle-ci
  (:require [babashka.http-client :as http]
            [babashka.fs :as fs]
            [babashka.tasks :as t]
            [cheshire.core :as json]
            [clj-commons.digest :as digest]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- tools-dir[]
  (let [home-dir (System/getProperty "user.home")]
    (str (fs/file home-dir "tools"))))

(defn- jdk-info
  "Use disco API to find our jdk"
  [{:keys [os jdk-major distro archive-type]}]
  (let [packages (-> (http/get "https://api.foojay.io/disco/v3.0/packages"
                               {:query-params {"distro" distro
                                               "package_type" "jdk"
                                               "latest" "available"
                                               "version" jdk-major
                                               "operating_system" os
                                               "architecture" "x64"
                                               "archive_type" archive-type}})
                     :body
                     (json/parse-string true)
                     :result)
        ;; seems temurin alpine release is classified under linux os rather than alpine_linux os
        ;; we are not interested in the alpine release
        packages (remove #(str/includes? (:filename %) "alpine") packages)]
    (when (not= 1 (count packages))
      (throw (ex-info (format "Expected 1 package to match, got: %s" (pr-str packages)) {})))
    (let [package (first packages)
          download-info (-> package
                            :links
                            :pkg_info_uri
                            http/get
                            :body
                            (json/parse-string true)
                            :result
                            first)]
      (merge package download-info))))

(defn- set-jdk-env [os install-dir]
  (let [jdk-dir (->> install-dir fs/list-dir (filter fs/directory?) first str)
        java-home (if (= "macos" os)
                    (str (fs/file jdk-dir "Contents/home"))
                    jdk-dir)
        java-bin (str (fs/file java-home "bin"))
        on-ci (System/getenv "CI")
        graal (str/includes? install-dir "graal")]
    (println "Setting up JAVA_HOME to" java-home)
    (let [dest-file (cond
                      (not on-ci) "<NOT-WRITTEN, NOT ON CI>"
                      ;; $PROFILE is not an environment variable, it must be fetched from powershell
                      (= "windows" os) (-> (t/shell {:out :string}
                                                    "powershell.exe -Command $PROFILE")
                                           :out str/trim)
                      :else (System/getenv "BASH_ENV"))
          env-entries (if (= "windows" os)
                        (cond-> [(format "$env:JAVA_HOME=\"%s\"" java-home)
                                 (format "$env:PATH=\"%s;$env:PATH\"" java-bin)]
                          ;; not really necessary but some like to set it
                          graal (conj (format "$env:GRAALVM_HOME=\"%s\"" java-home)))
                        (cond-> [(format "export JAVA_HOME=\"%s\"" java-home)
                                 (format "export PATH=\"%s:$PATH\"" java-bin)]
                          ;; not really necessary but some like to set it
                          graal (conj (format "export GRAALVM_HOME=\"%s\"" java-home))))]
      (println "writing to:" dest-file)
      (doseq [p env-entries]
        (do
          (println p) ;; nice for debugging locally and on circleci
          (when on-ci
            (spit dest-file (format "%s\n" p) :append true)))) )))

(defn- parse-jdk
  "Disco distro for graal includes the major.jdk ex graalvm_ce19, so we don't have client respecify it."
  [s]
  (or (some-> (re-find #"(graalvm_ce)(.+)" s) ((juxt first last)))
      (some-> (re-find #"(.+)@(.+)" s) rest)))

(defn- verify-checksum [download-file checksum_type checksum_uri]
  (println "Verifying sha")
  (when (not= "sha256" checksum_type)
    (throw (ex-info (format "Not handling checkum_type %s yet" checksum_type) {})))
  (let [expected-sha256 (-> (http/get checksum_uri)
                            :body
                            (str/split #" ") ;; ignore filename if present
                            first)
        actual-sha256 (digest/sha-256 (fs/file download-file))]
    (when (not= expected-sha256 actual-sha256)
      (throw (ex-info (format "Expected sha %s != actual %s" expected-sha256 actual-sha256) {})))))

;; bb task entry points
(defn install-jdk [& args]
  (let [[os distro-jdk-major] args
        [distro jdk-major] (parse-jdk distro-jdk-major)
        tools-dir (tools-dir)
        ext (if (= "windows" os) "zip" "tar.gz")
        {:keys [direct_download_uri
                checksum_uri
                checksum_type
                java_version]} (jdk-info {:os os
                                          :distro distro
                                          :jdk-major jdk-major
                                          :archive-type ext})
        install-dir (str (fs/file tools-dir (format "%s-%s" distro java_version)))
        download-file (str (fs/file install-dir (format "%s.%s" distro ext)))]
    (println "Installing" distro jdk-major)
    ;; allow for caching
    (if (fs/exists? install-dir)
      (println "Already installed to" install-dir)
      (try (println "Downloading" direct_download_uri)
           (fs/create-dirs install-dir)
           (io/copy
            (:body (http/get direct_download_uri {:as :stream}))
            (io/file download-file))
           (verify-checksum download-file checksum_type checksum_uri)
           (println "Unpacking download to" install-dir)
           (if (= "zip" ext)
             (fs/unzip download-file install-dir)
             (t/shell {:dir install-dir} "tar xzf" download-file))
           (fs/delete download-file)
           (catch Throwable ex
             (when (fs/exists? install-dir)
               (fs/delete-tree install-dir))
             (throw ex))))
    (set-jdk-env os install-dir)))

(comment

  (install-jdk "macos" "temurin@11")
  (install-jdk "windows" "temurin@17")
  (install-jdk "linux" "temurin@11")
  (install-jdk "linux" "graalvm_ce19")
  (install-jdk "windows" "graalvm_ce19")

  (install-jdk "linux" "graalvm@21" )
  (install-jdk "linux" "graalvm_community@21" )

  (install-jdk "linux" "temurin@8")

  (parse-jdk "graalvm_ce19")
  ;; => ["graalvm_ce19" "19"]

  (parse-jdk "foo@123")
  ;; => ("foo" "123")
)
