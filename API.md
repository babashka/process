# babashka.process 


Shell out in Clojure with simplicity and ease.
  If you are not yet familiar with the API, start reading the
  docstrings for [`process`](#process) and [`shell`](#shell).



## `$`
``` clojure

($ [& args])
```


Macro.


Convenience macro around [`process`](#process). Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L449-L478)
## `*defaults*`

Dynamic var containing overridable default options. Use
  `alter-var-root` to change permanently or `binding` to change temporarily.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L199-L204)
## `check`
``` clojure

(check [proc])
```


Takes a process, waits until is finished and throws if exit code is non-zero.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L99-L113)
## `destroy`
``` clojure

(destroy [proc])
```


Takes process or map
  with :proc (`java.lang.ProcessBuilder`). Destroys the process and
  returns the input arg.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L154-L160)
## `destroy-tree`
``` clojure

(destroy-tree [proc])
```


Same as [`destroy`](#destroy) but also destroys all descendants. JDK9+
  only. Falls back to [`destroy`](#destroy) on older JVM versions.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L164-L172)
## `exec`
``` clojure

(exec [cmd])
(exec [cmd {:keys [escape env extra-env], :or {escape default-escape}, :as opts}])
```


Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L504-L525)
## `pb`
``` clojure

(pb [cmd])
(pb [cmd opts])
(pb [prev cmd opts])
```


Returns a process builder (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L264-L274)
## `pipeline`
``` clojure

(pipeline [proc])
(pipeline [pb & pbs])
```


Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with `->` or [`pipeline`](#pipeline).
  - When passing two or more process builders created with `pb`: creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L394-L428)
## `process`
``` clojure

(process [cmd])
(process [cmd opts])
(process [prev cmd opts])
```


Takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record with:
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
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
      The `:out` and `:err` options support `:string` for writing to a string
      output. You will need to `deref` the process before accessing the string
      via the process's `:out`.
      For writing output to a file, you can set `:out` and `:err` to a `java.io.File` object, or a keyword:
       - `:write` + an additional `:out-file`/`:err-file` + file to write to the file.
       - `:append` + an additional `:out-file`/`:err-file` + file to append to the file.
   - `:inherit`: if true, sets `:in`, `:out` and `:err` to `:inherit`.
   - `:dir`: working directory.
   - `:env`, `:extra-env`: a map of environment variables. See [Add environment](/README.md#add-environment).
   - `:escape`: function that will applied to each stringified argument. On
      Windows this defaults to prepending a backslash before a double quote. On
      other operating systems it defaults to `identity`.
   - `:pre-start-fn`: a one-argument function that, if present, gets called with a
      map of process info just before the process is started. Can be useful for debugging
      or reporting. Any return value from the function is discarded. Map contents:
      - `:cmd` - a vector of the tokens of the command to be executed (e.g. `["ls" "foo"]`)
   - `:shutdown`: shutdown hook, defaults to `nil`. Takes process
      map. Typically used with [`destroy`](#destroy) or [`destroy-tree`](#destroy-tree) to ensure long
      running processes are cleaned up on shutdown.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L284-L377)
## `sh`
``` clojure

(sh [cmd])
(sh [cmd opts])
(sh [prev cmd opts])
```


Convenience function similar to `clojure.java.shell/sh` that sets
  `:out` and `:err` to `:string` by default and blocks. Similar to
  `cjs/sh` it does not check the exit code (this can be done with
  `check`).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L480-L494)
## `shell`
``` clojure

(shell [cmd & args])
```


Convenience function around [`process`](#process) that defaults to inheriting
  I/O: input is read and output is printed while the process
  runs. Throws on non-zero exit codes. Kills all subprocesses on
  shutdown. Optional options map can be passed as the first argument,
  followed by multiple command line arguments. The first command line
  argument is automatically tokenized. Examples:

  - `(shell "ls -la")`
  - `(shell {:out "/tmp/log.txt"} "git commit -m" "WIP")`

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L533-L561)
## `start`
``` clojure

(start [pb])
```


Takes a process builder, calls start and returns a process (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L430-L436)
## `tokenize`
``` clojure

(tokenize [s])
```


Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with `'` or `"`.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L15-L66)
