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

(defn- bb-test-env []
  (System/getenv "BABASHKA_TEST_ENV"))

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
      (if (= "jvm" (bb-test-env))
        (println "WARNING: Skipping test because bb not found in path or current dir.")
        (throw (ex-info "ERROR: bb not found in path or current dir" {})))))

(defn test-program
  "Return name of program for program resolution tests.
  Optionally specify `ext` (meaningful examples: `:bat` `:exe` `:com` `:cmd` `:sh`)"
  ([] (test-program nil))
  ([ext] (str "bbp-test-program" (when ext (str "." (name ext))))))

(defn- proj-relative-file
  "Support running tests from babashka itself, in this case process is a submodule"
  [f]
  (if (bb-test-env)
    (str (fs/file "process" f))
    f))

(defn real-dir
 "Abstractions for dirs for program resolution tests"
  [k]
  (case k
    :on-path (proj-relative-file "target/test/on-path")
    :workdir (proj-relative-file "target/test/workdir")
    ;; would be nice to isolate cwd but, it is our actual current working directory
    :cwd (System/getProperty "user.dir")))

(defn test-program-abs
  "Return canonical path for test program with (optinal) `ext` in `dir`."
  [dir ext]
  (-> (fs/file (real-dir dir) (test-program ext))
      fs/canonicalize str))

(defn- test-program-source
  "Return test program source for program resolution tests."
  [ext]
  (let [src-ext (if (= :com ext) :exe ext)] ;; map .com to .exe
    (proj-relative-file
      (str "test-resources/print-dirs." (name src-ext)))))

(defn program-scenario
  "Setup a scenario for program resolution tests.

  `scenario` describes what files should be in what dirs by their extensions, for example:
  - `{:cwd [:sh] :workdir [:sh] :on-path [:sh]}`
  - `{:cwd [:bat :exe] :on-path [:bat :cmd :com :exe :ps1]}`"
  [scenario]
  (doseq [p [:on-path :cwd :workdir]
          :let [dest-dir (fs/file (real-dir p))]]
    (fs/create-dirs dest-dir)
    (doseq [ext [:bat :cmd :exe :ps1 :com :sh]]
      (let [dest (fs/file (real-dir p) (test-program ext))]
        (fs/delete-if-exists dest)
        (when (some-> scenario p set ext)
          (fs/copy (test-program-source ext) dest))))))

(defmacro with-program-scenario
  "Sets up a program scenario for `body` and cleans up afterward."
  [scenario & body]
  `(try
    (program-scenario ~scenario)
    ~@body
    (finally
      (program-scenario {}))))

(defn etpo
  "Expected test program output from program resolution tests."
  [{:keys [exedir exename workdir]}]
  [(str "exepath: " (-> (fs/file (real-dir exedir) exename) fs/canonicalize str))
   (str "workdir: " (-> (real-dir workdir) fs/canonicalize str))])
