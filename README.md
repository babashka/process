# process

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: alpha.

This library will be included in
[babashka](https://github.com/borkdude/babashka) but is also intended as a JVM
library. You can play with this code in babashka today by using it as git dep:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Sdeps '{:deps {babashka/babashka.process {:sha "<latest-sha>" :git/url "https://github.com/babashka/babashka.process"}}}' -Spath)

user=> (require '[clojure.string :as str])
nil
user=> (require '[babashka.process :refer [$]])
nil
user=> (-> ($ ls -la) :out slurp str/split-lines first)
"total 136776"
```

## API

- `process`: takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record with
    - `:proc`: an instance of `java.lang.Process`
    - `:in`, `:err`, `:out`: the process's streams. To obtain a string from
      `:out` or `:err` you will typically use `slurp`. Slurping those streams
      will block the current thread until the process is finished.
    - `:cmd`: the command that was passed to create the process.
    - `:prev`: previous process record in case of a pipeline.

  The returned record may be passed to `deref`. Doing so will cause the current
  thread to block until the process is finished and will populate `:exit` with
  the exit code.

  Supported options:
    - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to or from the process's corresponding stream. May be set
      to `:inherit` for redirecting to the parent process's corresponding
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
    - `:dir`: working directory.
    - `:env`: a map of environment variables.

  Piping can be achieved with the `->` macro:

  ``` clojure
  (-> (process '[echo hello]) (process '[cat]) :out slurp) ;;=> "hello\n"
  ```
  or using the `pipeline` function (see below)

- `$`: convenience macro around `process`. Takes a number of objects, all which
  will be stringified with `str` and an optional option map as the last arg. Supports interpolation via `~`.

- `check`: takes a process, waits until is finished and
  throws if exit code is non-zero.

- `pb`: returns a `java.lang.ProcessBuilder` for use in `pipeline`.

- `pipeline`:
  - When passing a process, returns a vector of processes of a pipeline created with `->` or `pipeline`.
  - When passing two or more `ProcessBuilder` objects: creates a pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](#pipelines).

## Example usage

``` clojure
user=> (require '[babashka.process :refer [process check pipeline pb]])
```

Invoke `ls`:

``` clojure
user=> (-> (process '[ls]) :out slurp)
"LICENSE\nREADME.md\nsrc\n"
```

Change working directory:

``` clojure
user=> (-> (process '[ls] {:dir "test/babashka"}) :out slurp)
"process_test.clj\n"
```

Set the process environment.

``` clojure
user=> (-> (process '[sh -c "echo $FOO"] {:env {:FOO "BAR" }}) :out slurp)
"BAR\n"
```

The return value of `process` implements `clojure.lang.IDeref`. When
dereferenced, it will wait for the process to finish and will add the `:exit` value:

``` clojure
user=> (-> @(process '[ls foo]) :exit)
Execution error (ExceptionInfo) at babashka.process/check (process.clj:74).
ls: foo: No such file or directory
```

The function `check` takes a process, waits for it to finish and returns it. When
the exit code is non-zero, it will throw.

``` clojure
user=> (-> (process '[ls foo]) check :out slurp)
Execution error (ExceptionInfo) at babashka.process/check (process.clj:74).
ls: foo: No such file or directory
```

Redirect output to stdout:

``` clojure
user=> (do (process '[ls] {:out :inherit}) nil)
LICENSE		README.md	deps.edn	src		test
nil
```

Redirect output stream from one process to input stream of the next process:

``` clojure
(let [is (-> (process '[ls]) :out)]
  (process ["cat"] {:in is
                    :out :inherit})
    nil)
LICENSE
README.md
deps.edn
src
test
nil
```

Both `:in` and `:out` may contain objects that are compatible with `clojure.java.io/copy`:

``` clojure
user=> (with-out-str (check (process '[cat] {:in "foo" :out *out*})))
"foo"

user=> (with-out-str (check (process '[ls] {:out *out*})))
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

Forwarding the output of a process as the input of another process can also be done with thread-first:

``` clojure
(-> (process '[ls])
    (process '[grep "README"]) :out slurp)
"README.md\n"
```

`$` is a convenience macro around `process`:

``` clojure
(def config {:output {:format :edn}})
(-> ($ clj-kondo --config ~config --lint "src") :out slurp edn/read-string)
{:findings [], :summary {:error 0, :warning 0, :info 0, :type :summary, :duration 34}}
```

Demo of a `cat` process to which we send input while the process is running,
then close stdin and read the output of cat afterwards:

``` clojure
(ns cat-demo
  (:require [babashka.process :refer [process]]
            [clojure.java.io :as io]))

(def catp (process '[cat]))

(.isAlive (:proc catp)) ;; true

(def stdin (io/writer (:in catp)))

(binding [*out* stdin]
  (println "hello"))

(.close stdin)

(def exit (:exit @catp)) ;; 0

(.isAlive (:proc catp)) ;; false

(slurp (:out catp)) ;; "hello\n"
```

## Pipelines

The `pipeline` function returns a sequential of processes from a process that
was created with `->` or by passing multiple `ProcessBuilder` objects:

``` clojure
(mapv :cmd (pipeline (-> (process '[ls]) (process '[cat]))))
[["ls"] ["cat"]]

(mapv :cmd (pipeline (pb '[ls]) (pb '[cat])))
[["ls"] ["cat"]]
```

To obtain the right-most process from the pipeline, use `last` (or `peek`):

``` clojure
(-> (pipeline (pb ["ls"]) (pb ["cat"])) last :out slurp)
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

Calling `pipeline` on the right-most process returns the pipeline:

``` clojure
(def p (pipeline (pb ["ls"]) (pb ["cat"])))
#'user/p
(= p (pipeline (last p)))
true
```

To check an entire pipeline for non-zero exit codes, you can use:

``` clojure
(run! check (pipeline (-> (process '[ls "foo"]) (process '[cat]))))
Execution error (ExceptionInfo) at babashka.process/check (process.clj:37).
ls: foo: No such file or directory
```

Although you can create pipelines with `->`, for some applications it may be
preferable to create a pipeline with `pipeline` which defers to
`ProcessBuilder/startPipeline`. In the following case it takes a long time
before you would see any output due to buffering.

``` clojure
(future
  (loop []
    (spit "log.txt" (str (rand-int 10) "\n") :append true)
    (Thread/sleep 10)
    (recur)))

(-> (process '[tail -f "log.txt"])
    (process '[cat])
    (process '[grep "5"] {:out :inherit}))
```

The solution then it to use `pipeline` + `pb`:

``` clojure
(pipeline (pb '[tail -f "log.txt"])
          (pb '[cat])
          (pb '[grep "5"] {:out :inherit}))
```

The varargs arity of `pipeline` is only available in JDK9 or higher due to the
availability of `ProcessBuilder/startPipeline`. If you are on JDK8 or lower, the
following solution that reads the output of `tail` line by line may work for
you:

``` clojure
(def tail (process '[tail -f "log.txt"] {:err :inherit}))

(def cat-and-grep
  (-> (process '[cat]      {:err :inherit})
      (process '[grep "5"] {:out :inherit
                            :err :inherit})))

(binding [*in*  (io/reader (:out tail))
          *out* (io/writer (:in cat-and-grep))]
  (loop []
    (when-let [x (read-line)]
      (println x)
      (recur))))
```

Another solution is to let bash handle the pipes by shelling out with `bash -c`.

## Notes

### Script termination

Because `process` spawns threads for non-blocking I/O, you might have to run
`(shutdown-agents)` at the end of your Clojure JVM scripts to force
termination. Babashka does this automatically.

### Clojure.pprint

When pretty-printing a process, you will get an exception:

``` clojure
(require '[clojure.pprint :as pprint])
(pprint/pprint (process ["ls"]))
Execution error (IllegalArgumentException) at user/eval257 (REPL:1).
Multiple methods in multimethod 'simple-dispatch' match dispatch value: class babashka.process.Process -> interface clojure.lang.IDeref and interface clojure.lang.IPersistentMap, and neither is preferred
```

The reason is that a process is both a record and a `clojure.lang.IDeref` and
pprint does not have a preference for how to print this. This can be resolved
using:

``` clojure
(prefer-method pprint/simple-dispatch clojure.lang.IPersistentMap clojure.lang.IDeref)
```

## License

Copyright © 2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.
