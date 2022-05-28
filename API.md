# Table of contents
-  [`babashka.process`](#babashkaprocess)  - Shell out in Clojure with simplicity and ease.
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
    -  [`shell`](#shell) - Convenience function around <code>process</code> that defaults to inheriting
    -  [`start`](#start) - Takes a process builder, calls start and returns a process (as record).
    -  [`tokenize`](#tokenize) - Tokenize string to list of individual space separated arguments.
# babashka.process 


Shell out in Clojure with simplicity and ease.
  If you are not yet familiar with the API, start reading the
  docstrings for [`process`](#process) and [`shell`](#shell).



## [`$`](#$)
``` clojure

($ [& args])
```


Macro.


Convenience macro around [`shell`](#shell). Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via [`check`](#check)

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L449-L478)
## [`destroy`](#destroy)

Dynamic var containing overridable default options. Use
  [`destroy-tree`](#destroy-tree) to change permanently or [`destroy`](#destroy) to change temporarily.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L199-L204)
## [`destroy`](#destroy)
``` clojure

(check [proc])
```


Takes a process, waits until is finished and throws if exit code is non-zero.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L99-L113)
## [`pipeline`](#pipeline)
``` clojure

(destroy [proc])
```


Takes process or map
  with :proc (`java.lang.ProcessBuilder`). Destroys the process and
  returns the input arg.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L154-L160)
## [`pb`](#pb)
``` clojure

(destroy-tree [proc])
```


Same as [`pipeline`](#pipeline) but also destroys all descendants. JDK9+
  only. Falls back to [`pipeline`](#pipeline) on older JVM versions.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L164-L172)
## [`pb`](#pb)
``` clojure

(exec [cmd])
(exec [cmd {:keys [escape env extra-env], :or {escape default-escape}, :as opts}])
```


Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L504-L525)
## [`check`](#check)
``` clojure

(pb [cmd])
(pb [cmd opts])
(pb [prev cmd opts])
```


Returns a process builder (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L264-L274)
## [`sh`](#sh)
``` clojure

(pipeline [proc])
(pipeline [pb & pbs])
```


Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

  - When passing a process, returns a vector of processes of a pipeline created with [`destroy-tree`](#destroy-tree) or [`sh`](#sh).
  - When passing two or more process builders created with [`check`](#check): creates a
    pipeline as a vector of processes (JDK9+ only).

  Also see [Pipelines](/README.md#pipelines).
  

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L394-L428)
## [`shell`](#shell)
``` clojure

(process [cmd])
(process [cmd opts])
(process [prev cmd opts])
```


Takes a command (vector of strings or objects that will be turned
  into strings) and optionally a map of options.

  Returns: a record with:
   - [`process`](#process): an instance of [`start`](#start)
   - [`tokenize`](#tokenize), [`null`](#), [`null`](#): the process's streams. To obtain a string from
        [`null`](#) or [`null`](#) you will typically use [`null`](#) or use the [`null`](#)
         option (see below). Slurping those streams will block the current thread
         until the process is finished.
   - [`null`](#): the command that was passed to create the process.
   - [`null`](#): previous process record in case of a pipeline.

  The returned record can be passed to [`null`](#). Doing so will cause the current
  thread to block until the process is finished and will populate [`null`](#) with
  the exit code.

  Supported options:
   - [`tokenize`](#tokenize), [`null`](#), [`null`](#): objects compatible with [`null`](#) that
      will be copied to or from the process's corresponding stream. May be set
      to [`null`](#) for redirecting to the parent process's corresponding
      stream. Optional [`null`](#), [`null`](#) and [`null`](#) values will
      be passed along to [`null`](#).
      The [`null`](#) and [`null`](#) options support [`null`](#) for writing to a string
      output. You will need to [`null`](#) the process before accessing the string
      via the process's [`null`](#).
      For writing output to a file, you can set [`null`](#) and [`null`](#) to a [`null`](#) object, or a keyword:
       - [`null`](#) + an additional [`null`](#)/`:err-file` + file to write to the file.
       - [`null`](#) + an additional [`null`](#)/`:err-file` + file to append to the file.
   - [`null`](#): if true, sets [`tokenize`](#tokenize), [`null`](#) and [`null`](#) to [`null`](#).
   - [`null`](#): working directory.
   - [`null`](#), [`null`](#): a map of environment variables. See [Add environment](/README.md#add-environment).
   - [`null`](#): function that will applied to each stringified argument. On
      Windows this defaults to prepending a backslash before a double quote. On
      other operating systems it defaults to [`null`](#).
   - [`null`](#): a one-argument function that, if present, gets called with a
      map of process info just before the process is started. Can be useful for debugging
      or reporting. Any return value from the function is discarded. Map contents:
      - [`null`](#) - a vector of the tokens of the command to be executed (e.g. [`null`](#))
   - [`null`](#): shutdown hook, defaults to [`null`](#). Takes process
      map. Typically used with [`pipeline`](#pipeline) or [`pb`](#pb) to ensure long
      running processes are cleaned up on shutdown.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L284-L377)
## [`null`](#)
``` clojure

(sh [cmd])
(sh [cmd opts])
(sh [prev cmd opts])
```


Convenience function similar to [`null`](#) that sets
  [`null`](#) and [`null`](#) to [`null`](#) by default and blocks. Similar to
  [`null`](#) it does not check the exit code (this can be done with
  [`destroy`](#destroy)).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L480-L494)
## [`null`](#)
``` clojure

(shell [cmd & args])
```


Convenience function around [`shell`](#shell) that defaults to inheriting
  I/O: input is read and output is printed while the process
  runs. Throws on non-zero exit codes. Kills all subprocesses on
  shutdown. Optional options map can be passed as the first argument,
  followed by multiple command line arguments. The first command line
  argument is automatically tokenized. Examples:

  - [`null`](#)
  - [`null`](#)

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L533-L561)
## [`null`](#)
``` clojure

(start [pb])
```


Takes a process builder, calls start and returns a process (as record).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L430-L436)
## [`null`](#)
``` clojure

(tokenize [s])
```


Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with [`null`](#) or [`null`](#).

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L15-L66)
