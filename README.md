# process

[![Clojars Project](https://img.shields.io/clojars/v/babashka/process.svg)](https://clojars.org/babashka/process)
[![bb built-in](https://raw.githubusercontent.com/babashka/babashka/master/logo/built-in-badge.svg)](https://babashka.org)

Clojure library for shelling out / spawning sub-processes.

> **_NOTE:_**  When using process from babashka, this README assumes v1.0.168 or later.

## API

In 90% of the use cases you will probably need `shell` and for the remaining use
cases you will probably need a combination of `process` and `check`. Start
reading the docs for those, skim over the rest and revisit the remaining functions when you need them.

See [API docs](API.md) as generated by quickdoc.

## Installation

This library is included in [babashka](https://github.com/babashka/babashka)
since
[0.2.3](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#v023-2020-10-21)
but is also intended as a JVM library.

[![Clojars Project](https://img.shields.io/clojars/v/babashka/process.svg)](https://clojars.org/babashka/process)

## Usage

### Syntax

The functions `shell`, `process` and `exec` take an optional map followed by one or more strings:

``` clojure
(require '[babashka.process :refer [shell process exec]])

(shell "ls" "-la") ;; no options
(shell "ls -la" "dir") ;; first string is tokenized automatically, more strings may be provided
(shell {:dir "target"} "ls" "-la")
(process {:in "hello"} "cat")
(exec {:extra-env {"FOO" "BAR"}} "bash")
```

Previous versions of babashka process supported the `(process ["prog" "arg"]
{})` syntax. This syntax is no longer recommended, but is still supported to not
break existing programs.

### shell

Most commonly you will use `shell`. It executes a command and streams the output
to stdout and stderr while the process is running. The name `shell` comes from
"shelling out", but note that it does _not_ invoke a bash/zsh/cmd.exe shell: it
just starts an external program.

``` clojure
user=> (shell "ls" "-la")
total 144
drwxr-xr-x@ 22 borkdude  staff    704 Dec  4 13:39 .
drwxr-xr-x@ 75 borkdude  staff   2400 Dec  3 14:18 ..
drwxr-xr-x@  4 borkdude  staff    128 Mar 10  2022 .circleci
drwxr-xr-x@  5 borkdude  staff    160 Mar 10  2022 .clj-kondo
drwxr-xr-x@ 50 borkdude  staff   1600 Dec  3 20:55 .cpcache
```

The first string argument to `shell` is tokenized automatically: `"ls -la"` is
broken up into `"ls"` and `"-la"`, so `(shell "ls -la")` also works. This eases
the migration from existing bash scripts.

You can provide more arguments if you need to:

``` clojure
user=> (shell "ls -la" "src" "test")
src:
total 0
drwxr-xr-x@  3 borkdude  staff   96 Mar 10  2022 .
drwxr-xr-x@ 22 borkdude  staff  704 Dec  4 14:13 ..
drwxr-xr-x@  4 borkdude  staff  128 Dec  4 14:01 babashka

test:
total 0
drwxr-xr-x@  3 borkdude  staff   96 Mar 10  2022 .
drwxr-xr-x@ 22 borkdude  staff  704 Dec  4 14:13 ..
drwxr-xr-x@  3 borkdude  staff   96 Dec  4 14:01 babashka
```

This is particularly handy when you want to supply commands coming from the command line:

``` clojure
(apply shell "ls -la" *command-line-args*)
```

The `shell` function checks the command's exit code and throws if it is non-zero:

``` clojure
user=> (shell "ls nothing")
ls: nothing: No such file or directory
Execution error (ExceptionInfo) at babashka.process/check (process.cljc:113).
```

To avoid throwing when the command's exit code is non-zero, use `:continue true`.
You will still see the error printed to stderr, but no exception will be thrown. This is convenient
when you want to handle the `:exit` code yourself:

``` clojure
user=> (-> (shell {:continue true} "ls nothing") :exit)
ls: nothing: No such file or directory
1
```

> Note that `:continue true` only suppresses throwing an exception when the executed command's exit code is non-zero.
> Other exceptions can throw, for example, when the executable is not found.

To collect output as a `:string`, use the `:out :string` option as the first argument:

``` clojure
user=> (-> (shell {:out :string} "ls -la") :out str/split-lines first)
"total 144"
```

To also capture stderr to a `:string`, add in the `:err :string` option:

``` clojure
user=> (-> (shell {:out :string :err :string} "git conpig user.name")
           (select-keys [:out :err]))
{:out "borkdude\n", :err "WARNING: You called a Git command named 'conpig', which does not exist.\nContinuing in -1.1 seconds, assuming that you meant 'config'.\n"}
```

To redirect stderr to stdout specify the `:err :out` option:

``` clojure
user=> (-> (shell {:out :string :err :out} "git conpig user.name") :out)
"WARNING: You called a Git command named 'conpig', which does not exist.\nContinuing in -1.1 seconds, assuming that you meant 'config'.\nborkdude\n"
```

To change the working directory, use the `:dir` option:

``` clojure
user=> (-> (shell {:out :string :dir "src/babashka"} "ls -la") :out str/split-lines first)
"total 48"
```

To add environment variables, use `:extra-env`:

``` clojure
user=> (-> (shell {:out :string :extra-env {"FOO" "BAR"}} "bb -e '(System/getenv \"FOO\")'") :out print)
"BAR"
```

### process

The `shell` function is a combination of `process` and `deref` and `check`. The
`process` function is the lower level function of this library that doesn't make
any opinionated choices:

- It does not provide a default for `:out`, `:in` and `:err`: in `shell` these
  default to `:inherit` which means: read and write from and to the console. In
  `process` they default to the default of `java.lang.ProcessBuilder`.
- It does not wait until the process completes.
- It does not check the exit code and throw an exception.

Use `process` when you need to change one of the above and `shell`'s options do
not support it. In practice this means: whenever you need async processing,
e.g. reading output from a process while it is running.

The return value of `process` implements `clojure.lang.IDeref`. When
dereferenced, it will wait for the process to finish and will add the `:exit` value.

``` clojure
user=> (-> (process "ls foo") deref :exit)
1
```

The function `check` takes a process, waits for it to finish (so you can omit
`deref`) and returns it. When the exit code is non-zero, it will throw.

``` clojure
user=> (-> (process {:out :string} "ls") check :out str/split-lines first)
"API.md"
user=> (-> (process {:out :string} "ls foo") check :out str/split-lines first)
Execution error (ExceptionInfo) at babashka.process/check (process.clj:74).
ls: foo: No such file or directory
```

Both `:in`, `:out` may contain objects that are compatible with `clojure.java.io/copy`:

``` clojure
user=> (with-out-str (check (process {:in "foo" :out *out*} "cat")))
"foo"

user=> (->> (with-out-str (check (process {:out *out*} "ls"))) str/split-lines (take 2))
("API.md" "CHANGELOG.md")
```

The `:out` option also supports `:string` for collecting stdout into a string
and `:bytes` for getting the raw output as a byte array. You will need to
`deref` the process in order for the string or byte array to be there, since the
output can't be finalized if the process hasn't finished running:

``` clojure
user=> (-> @(process {:out :string} "ls") :out str/split-lines first)
"API.md"
user=> (-> @(process {:out :bytes} "head -c 10 /dev/urandom") :out seq)
(119 -43 -68 -64 -16 -56 32 45 86 56)
```

## Piping output

Both `shell` and `process` support piping output from one process to the next
using but note that `shell` writes the output to the system's stdout by
default, so you have to provide it with `{:out :string}` for the next process to
capture the input, while `process` uses the default `java.lang.ProcessBuilder`
setting which defaults to writing to a stream:

``` clojure
user=> (let [stream (-> (process "ls") :out)]
         @(process {:in stream
                    :out :inherit} "cat")
         nil)
API.md
CHANGELOG.md
LICENSE
README.md
...
```

Forwarding the output of a process as the input of another process can also be done with thread-first (`->`):

``` clojure
user=> (-> (process "ls")
           (process {:out :string} "grep README") deref :out)
"README.md\n"
```

## Redirecting output to a file

To write to a file use `:out :write` and set `:out-file` to a file:

``` clojure
user=> (require '[clojure.java.io :as io])
nil
user=> (do @(process {:out :write :out-file (io/file "/tmp/out.txt")} "ls") nil)
nil
user=> (slurp "/tmp/out.txt")
"CHANGELOG.md\nLICENSE\nREADME.md..."
```

or simply:

``` clojure
(do (shell {:out "/tmp/out.txt"} "ls") nil)
```

To append to a file, use `:out :append`:

``` clojure
user=> (do @(process {:out :append :out-file (io/file "/tmp/out.txt")} "ls") nil)
nil
user=> (slurp "/tmp/out.txt")
"CHANGELOG.md\nLICENSE\nREADME.md..."
```

## Feeding input

Here is an example of a `cat` process to which we send input while the process
is running, then close stdin and read the output of cat afterwards:

``` clojure
(ns cat-demo
  (:require [babashka.process :refer [process alive?]]
            [clojure.java.io :as io]))

(def catp (process "cat"))

(alive? catp) ;; true

(def stdin (io/writer (:in catp)))

(binding [*out* stdin]
  (println "hello"))

(.close stdin)

(slurp (:out catp)) ;; "hello\n"

(:exit @catp) ;; 0

(alive? catp) ;; false
```

## Processing streaming output

Here is an example where we read the output of `bb -o -e '(range)'`, an infinite
stream of numbers, line by line and print it ourselves:

``` clojure
(require '[babashka.process :as p :refer [process destroy-tree]]
         '[clojure.java.io :as io])

(def number-stream
  (process
   {:err :inherit
    :shutdown destroy-tree}
   "bb -o -e '(range)'"))

(with-open [rdr (io/reader (:out number-stream))]
  (binding [*in* rdr]
    (loop [max 10]
      (when-let [line (read-line)]
        (println :line line)
        (when (pos? max)
          (recur (dec max)))))))

;; kill the streaming bb process:
(p/destroy-tree number-stream)
```

## Printing command

The `:pre-start-fn` option can be used to report commands being run:

``` clojure
(require '[babashka.process :refer [process]])

(doseq [file ["LICENSE" "CHANGELOG.md"]]
  (-> (process
        {:out :string
         :pre-start-fn #(apply println "Running" (:cmd %))}
        "head" "-1" file)
      deref :out println))

Running head -1 LICENSE
Eclipse Public License - v 1.0

Running head -1 CHANGELOG.md
# Changelog
```

## sh

`sh` is a convenience function around `process` which sets `:out` and `:err` to
`:string` and blocks automatically, similar to `clojure.java.shell/sh`:

``` clojure
user=> (def config {:output {:format :edn}})
#'user/config
user=> (-> (sh ["clj-kondo" "--lint" "src"]) :out slurp edn/read-string)
{:findings [], :summary {:error 0, :warning 0, :info 0, :type :summary, :duration 34}}
```

## Tokenization

All of `shell`, `process` and `sh` support tokenization on the first string argument using `tokenize`:

``` clojure
user=> (require '[babashka.process :refer [sh tokenize]])
nil
user=> (tokenize "hello there")
["hello" "there"]
user=> (-> (sh "echo hello there") :out)
"hello there\n"
```

``` clojure
user=> (-> (sh {:in "(inc)"} "clj-kondo --lint -") :out)
"<stdin>:1:1: error: clojure.core/inc is called with 0 args but expects 1\nlinting took 10ms, errors: 1, warnings: 0\n"
```

## Output buffering

Note that `check` will wait for the process to end in order to check the exit
code. When the process has lots of data to write to stdout, it is recommended to
add an explicit `:out` option to prevent deadlock due to buffering. This example
will deadlock because the process is buffering the output stream but it's not
being consumed, so the process won't be able to finish:

``` clojure
user=> (-> (process {:in (apply str (repeat 1000000 "hello\n"))} "cat") check :out count)
```

The way to deal with this is providing an explicit `:out` option so the process
can finish writing its output:

``` clojure
user=> (-> (process {:out :string :in (apply str (repeat 1000000 "hello\n"))} "cat") check :out count)
6000000
```

## Add Environment

The `:env` option replaces your entire environment with the provided map. To add environment variables you can use `:extra-env` instead:

```clojure
:extra-env {"FOO" "BAR"}
```

> **Windows TIP**: Unlike in the CMD and Powershell shells, environment variable names are case sensitive for `:extra-env`.
For example, `"PATH"` will not update the value of `"Path"` on Windows.
Here's an [example of a babashka task](https://github.com/babashka/fs/blob/3b8010d1a0db166771ac7f47573ea09ed45abe33/bb.edn#L10-L11) that understands this nuance.

> **:env TIP**: An OS might have default environment variables it always includes.
For example, as of this writing, Windows always includes `SystemRoot` and macOS always includes `__CF_USER_TEXT_ENCODING`.

## Pipelines

The `pipeline` function returns a
[`sequential`](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/sequential?)
of processes from a process that was created with `->` or by passing multiple
objects created with `pb`:

``` clojure
user=> (require '[babashka.process :refer [pipeline pb process check]])
nil
user=> (mapv :cmd (pipeline (-> (process "ls") (process "cat"))))
[["ls"] ["cat"]]
user=> (mapv :cmd (pipeline (pb "ls") (pb "cat")))
[["ls"] ["cat"]]
```

To obtain the right-most process from the pipeline, use `last` (or `peek`):

``` clojure
user=> (-> (pipeline (pb "ls") (pb "cat")) last :out slurp)
"LICENSE\nREADME.md\ndeps.edn\nsrc\ntest\n..."
```

Calling `pipeline` on the right-most process returns the pipeline:

``` clojure
user=> (def p (pipeline (pb "ls") (pb "cat")))
#'user/p
user=> (= p (pipeline (last p)))
true
```

To check an entire pipeline for non-zero exit codes, you can use:

``` clojure
user=> (run! check (pipeline (pb "ls foo") (pb "cat")))
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

(-> (process "tail" "-f" "log.txt")
    (process "cat")
    (process {:out :inherit} "grep "5"))
```

The solution then it to use `pipeline` + `pb`:

``` clojure
(pipeline (pb "tail" "-f" "log.txt")
          (pb "cat")
          (pb {:out :inherit} "grep" "5"))
```

The varargs arity of `pipeline` is only available in JDK9 or higher due to the
availability of `ProcessBuilder/startPipeline`. If you are on JDK8 or lower, the
following solution that reads the output of `tail` line by line may work for
you:

``` clojure
user=> (require '[clojure.java.io :as io])
nil

(def tail (process {:err :inherit} "tail" "-f" "log.txt"))

(def cat-and-grep
  (-> (process {:err :inherit} "cat")
      (process {:out :inherit
                :err :inherit} "grep 5")))

(binding [*in*  (io/reader (:out tail))
          *out* (io/writer (:in cat-and-grep))]
  (loop []
    (when-let [x (read-line)]
      (println x)
      (recur))))
```

Another solution is to let bash handle the pipes by shelling out with `bash -c`.

## Program Resolution

### macOS & Linux
On macOS & Linux, programs are resolved the way you expect:

- `a` resolves against the system `PATH`
- `./a` resolves against `:dir` if specified, otherwise the current working directory
- `/some/absolute/a` resolves absolutely

In all cases, the working directory for `a` is `:dir`, if specified, otherwise your current working directory.

### Windows

Windows executable files have extensions, which, if not specified, are resolved in order: `.com`,`.exe`,`.bat`,`.cmd`.
Programs are resolved in directories using the same rules as macOS, Linux, and Windows PowerShell.

> **Windows .ps1 TIP**: Babashka process will never resolve to, and cannot launch, `.ps1` scripts directly.
To launch a `.ps1` script, you must do so through PowerShell.
Example:
> ```Clojure
> (p/shell "powershell.exe -File .\\a.ps1")
> ```

> **Windows TIP**: If you prefer a more CMD Shell-like experience where programs are resolved first in the current working directory, then on the `PATH`, and are OK with the security implications of doing so, you can override the default `:program-resolver` with your own.

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

## Differences with `clojure.java.process`

Clojure 1.12.0 features a new `clojure.java.process`
namespace. `babashka.process` predates it, but the API is very similar, although defaults will differ.

Note that the `exec` function in `babashka.process` does something very
different than the same-named function in `clojure.java.process`: in
`babashka.process` it replaces the parent process via a Unix `exec` call, while
in `clojure.java.process/exec` the process is launched as a child process.

Other notable differences:

- `clojure.java.process` does not do any tokenization, so it doesn't support passing a string like `"ls -la"`
- `clojure.java.process` does not have any Windows-specific support.

### Script termination

Because `process` spawns threads for non-blocking I/O, you might have to run
`(shutdown-agents)` at the end of your Clojure JVM scripts to force
termination. Babashka does this automatically.

## Clojure.pprint

When pretty-printing a process, by default you will get an exception:

``` clojure
user=> (require '[babashka.process :refer [process]])
nil
user=> (require '[clojure.pprint :as pprint])
nil
user=> (pprint/pprint (process "ls"))
Execution error (IllegalArgumentException) at user/eval257 (REPL:1).
Multiple methods in multimethod 'simple-dispatch' match dispatch value: class babashka.process.Process -> interface clojure.lang.IDeref and interface clojure.lang.IPersistentMap, and neither is preferred
```

The reason is that a process is both a record and a `clojure.lang.IDeref` and
pprint does not have a preference for how to print this. The recommended solution is to require the `babashka.process.pprint` namespace, which will define a `pprint` implementation for a `Process` record:
```clojure
user=> (require '[babashka.process.pprint])
nil

user=> (pprint/pprint (process "ls"))
{:proc
 #object[java.lang.ProcessImpl 0x1d61a348 "Process[pid=43771, exitValue=\"not exited\"]"],
 :exit nil,
...
```

### Promesa

On the JVM (not in bb), you can combine this library with [promesa](https://github.com/funcool/promesa)
in the following way. This requires `:exit-fn` which was released in version
`0.2.10`.

``` clojure
(require '[babashka.process :as proc]
         '[promesa.core :as prom])

(defn process
  "Returns promise that will be resolved upon process termination. The promise is rejected when the exit code is non-zero."
  [opts & cmd]
  (prom/create
   (fn [resolve reject]
     (let [exit-fn (fn [response]
                     (let [{:keys [exit] :as r} response]
                       (if (zero? exit)
                         (resolve r)
                         (reject r))))]
       (apply proc/process (assoc opts :exit-fn exit-fn) cmd)))))

(prom/let [ls (process
               {:out :string
                :err :inherit}
               "ls")
           ls-out (:out ls)]
  (prn ls-out))
```

## License

Copyright © 2020-2025 Michiel Borkent

Distributed under the EPL License. See LICENSE.
