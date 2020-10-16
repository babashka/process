# babashka.process

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: pre-alpha, WIP, still in development, breaking changes will be made.

This code may end up in [babashka](https://github.com/borkdude/babashka) but is
also intended as a JVM library. You can play with this code in babashka today,
by either copying the code or including this library as a git dep:

``` shell
$ export BABASHKA_CLASSPATH=$(clojure -Sdeps '{:deps {babashka/babashka.process {:sha "6c348b5213c0c77ebbdfcf2f5da71da04afee377" :git/url "https://github.com/babashka/babashka.process"}}}' -Spath)
$ bb -e "(require '[babashka.process :refer [process]]) (process [\"ls\"])"
```

Use any later SHA at your convenience.

## Example usage

``` clojure
user=> (require '[babashka.process :refer [process]])
```

Invoke `ls`:

``` clojure
user=> (-> (process ["ls"]) :out)
"LICENSE\nREADME.md\nsrc\n"
```

Invoke `ls` for different directory than current directory:

``` clojure
user=> (-> (process ["ls"] {:dir "test/babashka"}) :out)
"process_test.clj\n"
```

Set the process environment.

``` clojure
user=> (-> (process ["sh" "-c" "echo $FOO"] {:env {:FOO "BAR" }}) :out)
"BAR\n"
```

Output as stream:

``` clojure
user=> (-> (process ["ls"] {:out :stream}) :out slurp)
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

The exit code is returned as a delay. Realizing that delay will wait until the
process finishes.

``` clojure
user=> (-> (process ["ls" "foo"]) :exit deref)
0
```

By default, `process` throws when the exit code is non-zero:

``` clojure
user=> (process ["ls" "foo"])
Execution error (ExceptionInfo) at babashka.process/process (process.clj:54).
ls: foo: No such file or directory
```

Prevent throwing:

``` clojure
user=> (-> (process ["ls" "foo"] {:throw false}) :exit)
1
```

Capture the error output as a string or stream:

``` clojure
user=> (-> (process ["ls" "foo"] {:throw false}) :err)
"ls: foo: No such file or directory\n"

user=> (-> (process ["ls" "foo"] {:throw false :err :stream}) :err slurp)
"ls: foo: No such file or directory\n"
```

Redirect output to stdout:

``` clojure
user=> (do (process ["ls"] {:out :inherit}) nil)
LICENSE		README.md	deps.edn	src		test
nil
```

Redirect output stream from one process to input stream of the next process:

``` clojure
(let [is (-> (process ["ls"] {:out :stream}) :out)]
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
user=> (with-out-str (process ["cat"] {:in "foo" :out *out*}))
"foo"

user=> (with-out-str (process ["ls"] {:out *out*}))
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

Forwarding the output of a process as the input of another process can also be done with thread-first:

``` clojure
(-> (process ["ls"])
    (process ["grep" "README"]) :out)
"README.md\n"
```

Demo of a `cat` process to which we send input while the process is running, then close stdin and read the output of cat afterwards:

``` clojure
(ns cat-demo
  (:require [babashka.process :refer [process]]
            [clojure.java.io :as io]))

(def catp
  (process ["cat"] {:out :stream
                    :err :inherit}))

(.isAlive (:proc catp)) ;; true

(def stdin (io/writer (:in catp)))

(binding [*out* stdin]
  (println "hello"))

(.close stdin)

(def exit @(:exit catp)) ;; 0

(.isAlive (:proc catp)) ;; false

(slurp (:out catp)) ;; "hello\n"
```

## License

Copyright © 2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.
