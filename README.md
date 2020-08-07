# babashka.process

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: alpha, WIP. This code may end up in [babashka](https://github.com/borkdude/babashka).

## Example usage

``` clojure
user=> (require '[babashka.process :refer [process]])
```

Invoke `ls`:

``` clojure
user=> (-> (process ["ls"]) :out)
"LICENSE\nREADME.md\nsrc\n"
```

Output as stream:

``` clojure
user=> (-> (process ["ls"] {:out :stream}) :out slurp)
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n"
```

Exit code:

``` clojure
user=> (-> (process ["ls" "foo"]) :exit)
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

When `:wait` is set `false`, the exit code is wrapped in a future:

``` clojure
user=> (-> (process ["ls" "foo"] {:throw false :wait false}) :exit deref)
1
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

## License

Copyright © 2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.
