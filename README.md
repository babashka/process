# process

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: alpha.

This code may end up in [babashka](https://github.com/borkdude/babashka) but is
also intended as a JVM library. You can play with this code in babashka today,
by either copying the code or including this library as a git dep:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Sdeps '{:deps {babashka/babashka.process {:sha "<latest-sha>" :git/url "https://github.com/babashka/babashka.process"}}}' -Spath)

user=> (require '[clojure.string :as str])
nil
user=> (require '[babashka.process :refer [process]])
nil
user=> (-> (process ["ls" "-la"]) :out slurp str/split-lines first)
"total 136776"
```

## API

- `process`: takes a command (vector of strings) and optionally a map of
  options.

  Returns: a record with
    - `:proc`: an instance of `java.lang.Process`
    - `:in`, `:err`, `:out`: the process's streams. To obtain a string from
      `:out` or `:err` you will typicall use `slurp`. Slurping those streams
      will block the current thread until the process is finished.
    - `:exit`: delay containing the exit code. Realizing the delay will block
  current thread until process is finished.
    - `:command`: the command that was passed to create the process.

  The returned record implements `IDeref`, realizing it is equivalent to calling
  `check` on the record.

  Supported options:
    - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to or from the process's corresponding stream. May be set
      to `:inherit` for redirecting to the parent process's corresponding
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
    - `:dir`: working directory.
    - `:env`: a map of environment variables.

- `check`: takes a record as produced by `process`, checks the exit code of the
  underlying process (blocking until the process is finished) and throws if it
  was non-zero.

## Example usage

``` clojure
user=> (require '[babashka.process :refer [process check]])
```

Invoke `ls`:

``` clojure
user=> (-> (process ["ls"]) :out slurp)
"LICENSE\nREADME.md\nsrc\n"
```

Change working directory:

``` clojure
user=> (-> (process ["ls"] {:dir "test/babashka"}) :out slurp)
"process_test.clj\n"
```

Set the process environment.

``` clojure
user=> (-> (process ["sh" "-c" "echo $FOO"] {:env {:FOO "BAR" }}) :out slurp)
"BAR\n"
```

The exit code is returned as a delay. Realizing that delay will wait until the
process finishes.

``` clojure
user=> (-> (process ["ls" "foo"]) :exit deref)
1
```

The function `check` takes a process, waits for it to finish and returns it. When
the exit code is non-zero, it will throw.

``` clojure
user=> (-> (process ["ls" "foo"]) check :out slurp)
Execution error (ExceptionInfo) at babashka.process/check (process.clj:74).
ls: foo: No such file or directory
```

The return value of `process` implements `clojure.lang.IDeref`. When
dereferenced, it will execute `check`:

``` clojure
user=> (-> @(process ["ls" "foo"]) :out slurp)
Execution error (ExceptionInfo) at babashka.process/check (process.clj:74).
ls: foo: No such file or directory
```

Redirect output to stdout:

``` clojure
user=> (do (process ["ls"] {:out :inherit}) nil)
LICENSE		README.md	deps.edn	src		test
nil
```

Redirect output stream from one process to input stream of the next process:

``` clojure
(let [is (-> (process ["ls"]) :out)]
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
user=> (with-out-str (check (process ["cat"] {:in "foo" :out *out*})))
"foo"

user=> (with-out-str (check (process ["ls"] {:out *out*})))
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

Forwarding the output of a process as the input of another process can also be done with thread-first:

``` clojure
(-> (process ["ls"])
    (process ["grep" "README"]) :out slurp)
"README.md\n"
```

Demo of a `cat` process to which we send input while the process is running, then close stdin and read the output of cat afterwards:

``` clojure
(ns cat-demo
  (:require [babashka.process :refer [process]]
            [clojure.java.io :as io]))

(def catp (process ["cat"]))

(.isAlive (:proc catp)) ;; true

(def stdin (io/writer (:in catp)))

(binding [*out* stdin]
  (println "hello"))

(.close stdin)

(def exit @(:exit catp)) ;; 0

(.isAlive (:proc catp)) ;; false

(slurp (:out catp)) ;; "hello\n"
```

## Notes


### Script termination

Because `process` spawns threads for non-blocking I/O, you might have to run
`(shutdown-agents)` at the end of your Clojure JVM scripts to force
termination. Babashka does this automatically.

### Piping

When piping streams with infrequent output like in this example:

``` clojure
(ns pipes
  (:require [babashka.process :refer [process]]))

;; continually write to log
(future
  (loop []
    (spit "log.txt" (str (rand-int 10) "\n") :append true)
    (Thread/sleep 10)
    (recur)))

(-> (process ["tail" "-f" "log.txt"])
    (process ["cat"])
    (process ["grep" "5"] {:out :inherit}))
```

it may take a while before you will start seeing output, due to buffering.

If this is an issue, you can copy the output of `tail` to the input of `cat`
yourself line by line:

``` clojure
(def tail (process ["tail" "-f" "log.txt"] {:err :inherit}))

(def cat-and-grep
  (-> (process ["cat"]      {:err :inherit})
      (process ["grep" "5"] {:out :inherit
                             :err :inherit})))

(binding [*in*  (io/reader (:out tail))
          *out* (io/writer (:in cat-and-grep))]
  (loop []
    (when-let [x (read-line)]
      (println x)
      (recur))))
```

## License

Copyright © 2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.
