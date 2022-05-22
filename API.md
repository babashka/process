# Table of contents
-  [`babashka.process`](#babashkaprocess)
    -  [`$`](#$) - Convenience macro around <code>process</code>
    -  [`*defaults*`](#defaults) - Dynamic var containing overridable default options
    -  [`check`](#check) - Takes a process, waits until is finished and throws if exit code is non-zero.
    -  [`destroy`](#destroy) - Takes process or map
    -  [`destroy-tree`](#destroy-tree) - Same as <code>destroy</code> but also destroys all descendants
    -  [`exec`](#exec) - Replaces the current process image with the process image specified
    -  [`pb`](#pb) - Returns a process builder (as record).
    -  [`pipeline`](#pipeline) - Returns the processes for one pipe created with -> or creates
    -  [`process`](#process) - Takes a command (vector of strings or objects that will be turned
    -  [`sh`](#sh) - Convenience function similar to <code>clojure.java.shell/sh</code> that sets
    -  [`start`](#start) - Takes a process builder, calls start and returns a process (as record).
    -  [`tokenize`](#tokenize) - Tokenize string to list of individual space separated arguments.
# babashka.process 


## `$`
``` clojure

($ [& args])
```


Macro.


Convenience macro around `process`. Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L448-L477)
## `*defaults*`

Dynamic var containing overridable default options. Use
  `alter-var-root` to change permanently or `binding` to change temporarily.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L195-L200)
## `check`
``` clojure

(check [proc])
```


Takes a process, waits until is finished and throws if exit code is non-zero.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L96-L110)
## `destroy`
``` clojure

(destroy [proc])
```


Takes process or map
  with :proc (`java.lang.ProcessBuilder`). Destroys the process and
  returns the input arg.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L151-L157)
## `destroy-tree`
``` clojure

(destroy-tree [proc])
```


Same as `destroy` but also destroys all descendants. JDK9+ only.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L161-L168)
## `exec`
``` clojure

(exec [cmd])
(exec [cmd {:keys [escape env extra-env], :or {escape default-escape}, :as opts}])
```


Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L503-L524)
## `pb`
``` clojure

(pb [cmd])
(pb [cmd opts])
(pb [prev cmd opts])
```


Returns a process builder (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L258-L268)
## `pipeline`
``` clojure

(pipeline [proc])
(pipeline [pb & pbs])
```


Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with `->` or `pipeline`.
  - When passing two or more process builders created with `pb`: creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L393-L427)
## `process`
``` clojure

(process [cmd])
(process [cmd opts])
(process [prev cmd opts])
```


Takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record with
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
      or reporting. Any return value from the function is discarded.

      Map contents:
   - `:cmd` - a vector of the tokens of the command to be executed (e.g. `["ls" "foo"]`)
   - `:shutdown`: shutdown hook, defaults to `nil`. Takes process
      map. Typically used with `destroy` or `destroy-tree` to ensure long
      running processes are cleaned up on shutdown.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L278-L376)
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

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L479-L493)
## `start`
``` clojure

(start [pb])
```


Takes a process builder, calls start and returns a process (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L429-L435)
## `tokenize`
``` clojure

(tokenize [s])
```


Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with `'` or `"`.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L12-L63)
