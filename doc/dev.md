# Developer Documentation

Use bb tasks to run tests, run `bb tasks` to see what is available.

Test tasks all support cognitect test runner command line args, so you can for example:

```Shell
$ bb test:bb --var "babashka.process-test/tokenize-test"
```

## JVM Tests
The `test:jvm` task checks the first arg. If it starts with `:clj-`, it is assumed to be a
`deps.edn` alias used to select the Clojure version. Use `:clj-all` to repeat tests for all
`:clj-*` aliases. Default is the current version of Clojure.

Because `exec` requires native compilation, these JVM tests do not run
`babashka.process-exec-test`.

Testing default Clojure version:
```Shell
$ bb test:jvm
```

Testing under Clojure 1.9:
```Shell
$ bb test:jvm :clj-1.9
```

Testing all supported Clojure versions:
```Shell
$ bb test:jvm :clj-all
```

## Native/bb tests 
The `babashka.process/exec` can only be run when natively compiled by GraalVM native-image.
See `babashka.process-exec-test` namespace for some details.

Exec tests are supported by `run_exec.clj` which can be found under `~/test-native`.

The `test:native` task natively compiles `run_exec.clj` and exercises it through 
`babashka.process-exec-test`s. AOT compilation and native-image creation are not
repeated unless they seem stale. To force a full recompile, run `bb clean` before 
`bb test:native`.

The `test:bb` task runs all babashka.process tests. It runs `run_exec.clj` through bb.
The `run_exec.clj` code will reload the `babashka.process` namespace when the 
`babashka.process.test.reload` system property is set. A reload of `babashka.process` 
switches from the `babashka.process` that is built-in to bb to using `babashka.process` 
from sources.
