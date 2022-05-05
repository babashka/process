## babashka.process
### `$`
<code>[& args]</code><br>

Convenience macro around `process`. Takes command as varargs. Options can
  be passed via metadata on the form or as a first map arg. Supports
  interpolation via `~`

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L378-L407)
### `*defaults*`

Default settings for `process` invocations.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L189-L193)
### `check`
<code>[proc]</code><br>

Takes a process, waits until is finished and throws if exit code is non-zero.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L92-L106)
### `destroy`
<code>[proc]</code><br>

Takes process or map
  with :proc (`java.lang.ProcessBuilder`). Destroys the process and
  returns the input arg.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L147-L153)
### `destroy-tree`
<code>[proc]</code><br>

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L157-L162)
### `exec`
<code>[cmd]</code><br>
<code>[cmd {:keys [escape env extra-env] :or {escape default-escape} :as opts}]</code><br>

Replaces the current process image with the process image specified
  by the given path invoked with the given args. Works only in GraalVM
  native images.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L429-L450)
### `pb`
<code>[cmd]</code><br>
<code>[cmd opts]</code><br>
<code>[prev cmd opts]</code><br>

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L251-L260)
### `pipeline`
<code>[proc]</code><br>

Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L325-L331)
### `pipeline`
<code>[proc]</code><br>
<code>[pb & pbs]</code><br>

Returns the processes for one pipe created with -> or creates
  pipeline from multiple process builders.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L332-L359)
### `process`
<code>[cmd]</code><br>
<code>[cmd opts]</code><br>
<code>[prev cmd opts]</code><br>

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L270-L322)
### `sh`
<code>[cmd]</code><br>
<code>[cmd opts]</code><br>
<code>[prev cmd opts]</code><br>

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L409-L419)
### `start`
<code>[pb]</code><br>

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L361-L365)
### `tokenize`
<code>[s]</code><br>

Tokenize string to list of individual space separated arguments.
  If argument contains space you can wrap it with `'` or `"`.

[Source](https://github.com/babashka/process/blob/master/src/babashka/process.cljc#L12-L59)
