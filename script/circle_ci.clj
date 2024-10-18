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
  [{:keys [os arch jdk-major distro archive-type]}]
  (let [packages (-> (http/get "https://api.foojay.io/disco/v3.0/packages"
                               {:query-params {"distro" distro
                                               "package_type" "jdk"
                                               "latest" "available"
                                               "version" jdk-major
                                               "operating_system" os
                                               "architecture" arch
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
        (println p) ;; nice for debugging locally and on circleci
        (when on-ci
          (spit dest-file (format "%s\n" p) :append true))) )))

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

(defn install-jdk* [{:keys [os arch distro-jdk-major]}]
  (let [[distro jdk-major] (parse-jdk distro-jdk-major)
        tools-dir (tools-dir)
        ext (if (= "windows" os) "zip" "tar.gz")
        {:keys [direct_download_uri
                checksum_uri
                checksum_type
                operating_system
                architecture
                java_version]} (jdk-info {:os os
                                          :arch arch
                                          :distro distro
                                          :jdk-major jdk-major
                                          :archive-type ext})
        install-dir (str (fs/file tools-dir (format "%s-%s-%s-%s" distro operating_system architecture java_version)))
        download-file (str (fs/file install-dir (format "%s.%s" distro ext)))]
    (println "Installing" distro jdk-major "for" os arch)
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

;; bb task entry points
(defn install-jdk [distro-jdk-major]
  (let [os (str/lower-case (System/getProperty "os.name"))
        os (cond
             (.startsWith os "windows") "windows"
             (.startsWith os "mac os x") "macos"
             :else "linux")
        arch (System/getProperty "os.arch")]
    (install-jdk* {:os os
                   :arch arch
                   :distro-jdk-major distro-jdk-major})))

(comment

  (System/getProperty "os.arch")
  ;; => "amd64"
  (System/getProperty "os.name")
  ;; => "Linux"

  (-> (jdk-info {:os "macos"
                 :arch "x64"
                 :jdk-major "11"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)
  ;; => "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.23%2B9/OpenJDK11U-jdk_x64_mac_hotspot_11.0.23_9.tar.gz"

  ;; => "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.23%2B9/OpenJDK11U-jdk_x64_mac_hotspot_11.0.23_9.tar.gz"
  (-> (jdk-info {:os "macos"
                 :arch "amd64"
                 :jdk-major "11"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)
  ;; => "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.23%2B9/OpenJDK11U-jdk_x64_mac_hotspot_11.0.23_9.tar.gz"
  (-> (jdk-info {:os "macos"
                 :arch "aarch64"
                 :jdk-major "11"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)
  ;; => "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.23%2B9/OpenJDK11U-jdk_aarch64_mac_hotspot_11.0.23_9.tar.gz"

  (-> (jdk-info {:os "linux"
                 :arch "amd64"
                 :jdk-major "17"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)



  (-> (jdk-info {:os "linux"
                 :os-arch "x64"
                 :jdk-major "11"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)
  ;; => "https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.23%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.23_9.tar.gz"

  ;; jdk8 is not available on Apple silicon:
  (-> (jdk-info {:os "macos"
                 :os-arch "aarch64"
                 :jdk-major "8"
                 :distro "temurin"
                 :archive-type "tar.gz"})
      :direct_download_uri)
  ;; => clojure.lang.ExceptionInfo: Expected 1 package to match, got: () user /home/lee/proj/oss/babashka/process/script/circle_ci.clj:32:7


  (install-jdk "temurin@17")

  (install-jdk* {:os "macos" :arch "x64" :distro-jdk-major "temurin@11"})
  (install-jdk* {:os "macos" :arch "arm64" :distro-jdk-major "temurin@11"})
  (install-jdk* {:os "windows" :arch "x64" :distro-jdk-major "temurin@17"})
  (install-jdk* {:os "linux" :arch "x64" :distro-jdk-major "temurin@17"})

  (install-jdk* {:os "linux" :arch "x64" :distro-jdk-major "graalvm@21"} )
  (install-jdk* {:os "linux" :arch "x64" :distro-jdk-major "graalvm_community@21"} )

  (install-jdk* {:os "linux" :arch "x64" :distro-jdk-major "temurin@8"})

  (parse-jdk "graalvm_ce19")
  ;; => ["graalvm_ce19" "19"]

  (parse-jdk "foo@123")
  ;; => ("foo" "123")

:eoc)
