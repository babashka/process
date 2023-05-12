(ns babashka.process.test-utils
  "Common test utilitities used by not only our tests but also bb build scripting."
  (:require  [babashka.fs :as fs]
             [clojure.java.shell :as shell]
             [clojure.string :as str]))

(defn *find-bb
  "Find bb on current directory else on path.
  Return unresolved bb, it is the job of babashka process, at least on Windows to resolve the exe."
  []
  (or (and (fs/which "./bb") "./bb")
      (and (fs/which "bb") "bb")))

(def os
  "Sometimes we need to know if we are running on macOS, in those cases fs/windows? does not cut it"
  (condp re-find (str/lower-case (System/getProperty "os.name"))
    #"win" :win
    #"mac" :mac
    #"(nix|nux|aix)" :linux ;; calling unix variants linux because that is more likely the case
    #"sunos" :solaris
    :unknown))

(def run-exec-exe
  "Including the os name in the run-exec exe is helpful to devs using shared folders on multiple OSes.
  On windows exe name is naturally distinguished, but on macOS and Linux it is not."
  (str (name os) "-run-exec"))

(defn always-present-env-vars
  "Even when requesting an empty environment, some OSes do not return an empty environment"
  []
  (os {:mac ["__CF_USER_TEXT_ENCODING"]
       :win ["SystemRoot"]}))

(defn print-test-env[]
  (let [bb (*find-bb)]
    (println "- calling babashka as:" (if bb (str bb) "<not found>"))
    (when bb
      (println (format "  - which resolves to: %s" (-> (fs/which bb) fs/canonicalize)))
      (println (format "  - %s" (-> (shell/sh (-> (fs/which bb) str) "--version") :out str/trim))))))

(def wd
  "Wee dummy script location. Understands that these tests are run from babashka/process or babashka."
  (->> ["process/script/wd.clj"
        "script/wd.clj"]
       (filter fs/exists?)
       first))

(defn ols
  "Return s with line separators converted for current operating system"
  [s]
  (str/replace s "\n" (System/getProperty "line.separator")))

(defn find-bb
  "Tests launch an os-agnostic bb script that emits/behaves in ways useful
  to exercising babashka.process. Any test that uses bb should find it via
  this function.

  Babashka proper also runs these tests under the jvm and native-image.
  For babashka proper jvm tests, bb does not exist in the CI environment.
  This function allows jvm tests to be skipped without error in this scenario.
  This is fine because jvm tests are simply a pre-cursor/quick-way to run tests
  before they are repeated for native-image where any test failures will ultimately
  be caught."
  []
  (or (*find-bb)
      (if (= "jvm" (System/getenv "BABASHKA_TEST_ENV"))
        (println "WARNING: Skipping test because bb not found in path or current dir.")
        (throw (ex-info "ERROR: bb not found in path or current dir" {})))))
