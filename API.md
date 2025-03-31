# Table of contents
-  [`babashka.process`](#babashka.process)  - Clojure library for shelling out / spawning sub-processes.
    -  [`$`](#babashka.process/$) - Convenience macro around <code>process</code>.
    -  [`*defaults*`](#babashka.process/*defaults*) - Dynamic var containing overridable default options.
    -  [`alive?`](#babashka.process/alive?) - Returns <code>true</code> if the process is still running and false otherwise.
    -  [`check`](#babashka.process/check) - Takes a process, waits until is finished and throws if exit code is non-zero.
    -  [`destroy`](#babashka.process/destroy) - Destroys the process and returns the input arg.
    -  [`destroy-tree`](#babashka.process/destroy-tree) - Same as <code>destroy</code> but also destroys all descendants.
    -  [`exec`](#babashka.process/exec) - Replaces the current process image with the process image specified by the given path invoked with the given args.
    -  [`parse-args`](#babashka.process/parse-args) - Parses arguments to <code>process</code> to map with: * <code>:prev</code>: a (previous) process whose input is piped into the current process * <code>:cmd</code>: a vector of command line argument strings * <code>:opts</code>: options map Note that this function bridges the legacy <code>[cmds ?opts]</code> syntax to the newer recommended syntax <code>[?opts & args]</code> and therefore looks unnecessarily complex.
    -  [`pb`](#babashka.process/pb) - Returns a process builder (as record).
    -  [`pipeline`](#babashka.process/pipeline) - Returns the processes for one pipe created with -> or creates pipeline from multiple process builders.
    -  [`process`](#babashka.process/process) - Creates a child process.
    -  [`process*`](#babashka.process/process*) - Same as with <code>process</code> but called with parsed arguments (the result from <code>parse-args</code>).
    -  [`sh`](#babashka.process/sh) - Convenience function similar to <code>clojure.java.shell/sh</code> that sets <code>:out</code> and <code>:err</code> to <code>:string</code> by default and blocks.
    -  [`shell`](#babashka.process/shell) - Convenience function around <code>process</code> that was originally in <code>babashka.tasks</code>.
    -  [`start`](#babashka.process/start) - Takes a process builder, calls start and returns a process (as record).
    -  [`tokenize`](#babashka.process/tokenize) - Tokenize string to list of individual space separated arguments.

-----
# <a name="babashka.process">babashka.process</a>


Clojure library for shelling out / spawning sub-processes.
  If you are not yet familiar with the API, start reading the
  docstrings for [`process`](#babashka.process/process) and [`shell`](#babashka.process/shell).




## <a name="babashka.process/$">`$`</a><a name="babashka.process/$"></a>
``` clojure

($ opts? & args)
```
Function.

Convenience macro around [`process`](#babashka.process/process). Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L563-L593">Source</a></sub></p>

## <a name="babashka.process/*defaults*">`*defaults*`</a><a name="babashka.process/*defaults*"></a>




Dynamic var containing overridable default options. Use
  `alter-var-root` to change permanently or `binding` to change temporarily.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L215-L220">Source</a></sub></p>

## <a name="babashka.process/alive?">`alive?`</a><a name="babashka.process/alive?"></a>
``` clojure

(alive? p)
```

Returns `true` if the process is still running and false otherwise.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L694-L697">Source</a></sub></p>

## <a name="babashka.process/check">`check`</a><a name="babashka.process/check"></a>
``` clojure

(check proc)
```

Takes a process, waits until is finished and throws if exit code is non-zero.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L95-L115">Source</a></sub></p>

## <a name="babashka.process/destroy">`destroy`</a><a name="babashka.process/destroy"></a>
``` clojure

(destroy proc)
```

Destroys the process and returns the input arg. Takes process or map
  with :proc (`java.lang.ProcessBuilder`). 
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L156-L161">Source</a></sub></p>

## <a name="babashka.process/destroy-tree">`destroy-tree`</a><a name="babashka.process/destroy-tree"></a>
``` clojure

(destroy-tree proc)
```

Same as [[`destroy`](#babashka.process/destroy)](#babashka.process/destroy) but also destroys all descendants. JDK9+
  only. Falls back to [[`destroy`](#babashka.process/destroy)](#babashka.process/destroy) on older JVM versions.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L165-L173">Source</a></sub></p>

## <a name="babashka.process/exec">`exec`</a><a name="babashka.process/exec"></a>
``` clojure

(exec opts? & args)
```

Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images (which includes babashka).

  Supported `opts`
  - `:arg0`: override first argument (the executable). No-op on Windows.
  - `:cmd`, `:env`,`:extra-env`, `:escape`,`:pre-start-fn` : see [`process`](#babashka.process/process).
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L618-L656">Source</a></sub></p>

## <a name="babashka.process/parse-args">`parse-args`</a><a name="babashka.process/parse-args"></a>
``` clojure

(parse-args args)
```

Parses arguments to [`process`](#babashka.process/process) to map with:

  * `:prev`: a (previous) process whose input is piped into the current process
  * `:cmd`: a vector of command line argument strings
  * `:opts`: options map

  Note that this function bridges the legacy `[cmds ?opts]` syntax to
  the newer recommended syntax `[?opts & args]` and therefore looks
  unnecessarily complex.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L284-L333">Source</a></sub></p>

## <a name="babashka.process/pb">`pb`</a><a name="babashka.process/pb"></a>
``` clojure

(pb & args)
```

Returns a process builder (as record).
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L335-L342">Source</a></sub></p>

## <a name="babashka.process/pipeline">`pipeline`</a><a name="babashka.process/pipeline"></a>
``` clojure

(pipeline proc)
(pipeline pb & pbs)
```

Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with `->` or [`pipeline`](#babashka.process/pipeline).
  - When passing two or more process builders created with [`pb`](#babashka.process/pb): creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L508-L542">Source</a></sub></p>

## <a name="babashka.process/process">`process`</a><a name="babashka.process/process"></a>
``` clojure

(process opts? & args)
```

Creates a child process. Takes a command (vector of strings or
  objects that will be turned into strings) and optionally a map of
  options.

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
   - `:cmd`: a vector of strings. A single string can be tokenized into a vector of strings with [`tokenize`](#babashka.process/tokenize).
      Overrides the variadic `args` argument.
   - `:in`, `:out`, `:err`: objects compatible with `clojure.java.io/copy` that
      will be copied to from the process's corresponding stream.
      May be set to `:inherit` for redirecting to the parent process's corresponding
      stream. Optional `:in-enc`, `:out-enc` and `:err-enc` values will
      be passed along to `clojure.java.io/copy`.
      For redirecting to Clojure's `*in*`, `*out*` or `*err*` stream, set
      the corresponding option accordingly.
      The `:out` and `:err` options support `:string` for writing to a string
      output and `:bytes` for writing to a byte array. You will need to `deref`
      the process before accessing the string or byte array via the process's `:out`.
      To redirect `:err` to `:out`, specify `:err :out`.
      For writing output to a file, you can set `:out` and `:err` to a `java.io.File` object, or a keyword:
       - `:write` + an additional `:out-file`/`:err-file` + file to write to the file.
       - `:append` + an additional `:out-file`/`:err-file` + file to append to the file.
   - `:prev`: output from `:prev` will be piped to the input of this process. Overrides `:in`.
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
      map. Typically used with [`destroy`](#babashka.process/destroy) or [`destroy-tree`](#babashka.process/destroy-tree) to ensure long
      running processes are cleaned up on shutdown. The shutdown hook is
      executed as soon as the child process ends.
   - `:exit-fn`: a function which is executed upon exit. Receives process map as argument. Only supported in JDK11+.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L438-L491">Source</a></sub></p>

## <a name="babashka.process/process*">`process*`</a><a name="babashka.process/process*"></a>
``` clojure

(process* {:keys [prev cmd opts]})
```

Same as with [`process`](#babashka.process/process) but called with parsed arguments (the result from [`parse-args`](#babashka.process/parse-args))
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L354-L436">Source</a></sub></p>

## <a name="babashka.process/sh">`sh`</a><a name="babashka.process/sh"></a>
``` clojure

(sh opts? & args)
```

Convenience function similar to `clojure.java.shell/sh` that sets
  `:out` and `:err` to `:string` by default and blocks. Similar to
  `cjs/sh` it does not check the exit code (this can be done with
  [`check`](#babashka.process/check)).
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L595-L605">Source</a></sub></p>

## <a name="babashka.process/shell">`shell`</a><a name="babashka.process/shell"></a>
``` clojure

(shell opts? & args)
```

Convenience function around [[`process`](#babashka.process/process)](#babashka.process/process) that was originally in `babashka.tasks`.
  Defaults to inheriting I/O: input is read and output is printed
  while the process runs. Defaults to throwing on non-zero exit codes. Kills all
  subprocesses on shutdown. Optional options map can be passed as the
  first argument, followed by multiple command line arguments. The
  first command line argument is automatically tokenized. Counter to
  what the name of this function may suggest, it does not start a
  new (bash, etc.) shell, it just shells out to a program. As such, it
  does not support bash syntax like `ls *.clj`.

  Supported options:
  - `:continue`: if `true`, suppresses throwing on non-zero process exit code.
  - see [[`process`](#babashka.process/process)](#babashka.process/process) for other options

  Examples:

  - `(shell "ls -la")` ;; `"ls -la"` is tokenized as `["ls" "-la"]`
  - `(shell {:out "/tmp/log.txt"} "git commit -m" "WIP")` ;; `"git commit -m"` is tokenized as `["git" "commit" "-m"]` and `"WIP"` is an additional argument

  Also see the [`shell`](#babashka.process/shell) entry in the babashka book [here](https://book.babashka.org/#_shell).
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L664-L692">Source</a></sub></p>

## <a name="babashka.process/start">`start`</a><a name="babashka.process/start"></a>
``` clojure

(start pb)
```

Takes a process builder, calls start and returns a process (as record).
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L544-L550">Source</a></sub></p>

## <a name="babashka.process/tokenize">`tokenize`</a><a name="babashka.process/tokenize"></a>
``` clojure

(tokenize s)
```

Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with `'` or `"`.
<p><sub><a href="https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L15-L62">Source</a></sub></p>
