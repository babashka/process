# process

[![Clojars Project](https://img.shields.io/clojars/v/babashka/process.svg)](https://clojars.org/babashka/process)

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: alpha.

This library is included in
[babashka](https://github.com/babashka/babashka) since [0.2.3](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v023-2020-10-21) but is also intended as a JVM
library:

``` clojure
$ clojure -Sdeps '{:deps {babashka/process {:mvn/version "0.0.1"}}}'

user=> (require '[clojure.string :as str])
nil
user=> (require '[babashka.process :refer [process check]])
nil
user=> (-> (process ["ls" "-la"] {:out :string}) check :out str/split-lines first)
"total 136776"
```

## Differences with `clojure.java.shell/sh`

If `clojure.java.shell` works for your purposes, keep using it. But there are
contexts in which you need more flexibility. The major differences compared with
this library:

- `sh` is blocking, `process` makes blocking explicit via `deref`
- `sh` focuses on convenience but limits what you can do with the underlying
  process, `process` exposes as much as possible while still offering an ergonomic
  API
- `process` supports piping processes via `->` or `pipeline`
- `sh` offers integration with `clojure.java.io/copy` for `:in`, `process` extends
  this to `:out` and `:err`

## API

You will probably mostly need `process` and `check` so it would be good
to start reading the docs for these. Skim over the rest and come back when you
need it.

- `process`: takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record (called "the process" in this README) with
    - `:proc`: an instance of `java.lang.Process`
    - `:in`, `:err`, `:out`: the process's streams. To obtain a string from
      `:out` or `:err` you will typically use `slurp` or use the `:string`
      option (see below). Slurping those streams will block the current thread
      until the process is finished.
    - `:cmd`: the command that was passed to create the process.
    - `:prev`: previous process record in case of a pipeline.

  The returned record can be passed to `deref`. Doing so will cause the current
  thread to block until the process is finished and will populate `:exit` with
  the exit code.

  Supported options:
    - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to or from the process's corresponding stream. May be set
      to `:inherit` for redirecting to the parent process's corresponding
      stream.Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.

      The `:out` and `:err` options support `:string` for writing to a string
      output. You will need to `deref` the process before accessing the string
      via the process's `:out`.

    - `:inherit`: if true, sets `:in`, `:out` and `:err` to `:inherit`.
    - `:dir`: working directory.
    - `:env`, `:extra-env`: a map of environment variables. See [Add environment](#add-environment).
    - `:escape`: function that will applied to each stringified argument. On
      Windows this defaults to prepending a backslash before a double quote. On
      other operating systems it defaults to `identity`.
    - `:shutdown`: shutdown hook, defaults to `nil`. Takes process
      map. Typically used with `destroy` or `destroy-tree` to ensure long
      running processes are cleaned up on shutdown.

  Piping can be achieved with the `->` macro:

  ``` clojure
  (-> (process '[echo hello]) (process '[cat]) :out slurp) ;;=> "hello\n"
  ```
  or using the `pipeline` function (see below)

- `check`: takes a process, waits until is finished and
  throws if exit code is non-zero.

- `$`: convenience macro around `process`. Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`.

- `sh`: convenience function similar to `clojure.java.shell/sh` that sets `:out`
  and `:err` to `:string` by default and blocks. Similar to `cjs/sh` it does not
  check the exit code (this can be done with `check`).

- `*defaults*`: dynamic var containing overridable default options. Use
  `alter-var-root` to change permanently or `binding` to change temporarily.

- `destroy`: function of process or map with `:proc`
  (`java.lang.ProcessBuilder`). Destroys the process and returns the input arg.

- `destroy-tree`: same as `destroy` but also destroys all descendants. JDK9+ only.

- `pb`: returns a process builder (as record).

- `start`: takes a process builder, calls start and returns a process (as record).

- `pipeline`:
  - When passing a process, returns a vector of processes of a pipeline created with `->` or `pipeline`.
  - When passing two or more process builders created with `pb`: creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](#pipelines).

## Usage

``` clojure
user=> (require '[babashka.process :refer [process $ check]])
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

Both `:in`, `:out` may contain objects that are compatible with `clojure.java.io/copy`:

``` clojure
user=> (with-out-str (check (process '[cat] {:in "foo" :out *out*})))
"foo"

user=> (with-out-str (check (process '[ls] {:out *out*})))
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

The `:out` option also supports `:string`. You will need to `deref` the process
in order for the string to be there:

``` clojure
user=> (-> @(process '[ls] {:out :string}) :out)
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
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

Forwarding the output of a process as the input of another process can also be done with thread-first:

``` clojure
(-> (process '[ls])
    (process '[grep "README"]) :out slurp)
"README.md\n"
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

(slurp (:out catp)) ;; "hello\n"

(def exit (:exit @catp)) ;; 0

(.isAlive (:proc catp)) ;; false
```

## $ and sh

`$` is a convenience macro around `process`:

``` clojure
(def config {:output {:format :edn}})
(-> ($ clj-kondo --config ~config --lint "src") deref :out slurp edn/read-string)
{:findings [], :summary {:error 0, :warning 0, :info 0, :type :summary, :duration 34}}
```

`sh` is a convenience function around `process` which sets `:out` and `:err` to
`:string` and blocks automatically, similar to `clojure.java.shell/sh` and unlike `$`:

``` clojure
(def config {:output {:format :edn}})
(-> (sh ["clj-kondo" "--lint" "src"]) :out slurp edn/read-string)
{:findings [], :summary {:error 0, :warning 0, :info 0, :type :summary, :duration 34}}
```

## Tokenization

Both `process`, `$` and `sh` support tokenization when passed a single string argument:

``` clojure
(-> (sh "echo hello there") :out)
"hello there\n"
```

``` clojure
(-> (sh "clj-kondo --lint -" {:in "(inc)"}) :out print)
<stdin>:1:1: error: clojure.core/inc is called with 0 args but expects 1
linting took 11ms, errors: 1, warnings: 0
```

## Output buffering

Note that `check` will wait for the process to end in order to check the exit
code. When the process has lots of data to write to stdout, it is recommended to
add an explicit `:out` option to prevent deadlock due to buffering. This example
will deadlock because the process is buffering the output stream but it's not
being consumed, so the process won't be able to finish:

``` clojure
user=> (-> (process ["cat"] {:in (slurp "https://datahub.io/datahq/1mb-test/r/1mb-test.csv")}) check :out slurp count)
```

The way to deal with this is providing an explicit `:out` option so the process
can finish writing its output:

``` clojure
user=> (-> (process ["cat"] {:in (slurp "https://datahub.io/datahq/1mb-test/r/1mb-test.csv") :out :string}) check :out count)
1043005
```

## Add Environment

The `:env` option replaces your entire environment with the provided map. To add environment variables you can use `:extra-env` instead:

```clojure
:extra-env {"FOO" "BAR"}
```

## Pipelines

The `pipeline` function returns a
[`sequential`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/sequential?)
of processes from a process that was created with `->` or by passing multiple
objects created with `pb`:

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
