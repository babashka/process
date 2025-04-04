# Changelog

[Babashka process](https://github.com/babashka/process)
Clojure library for shelling out / spawning sub-processes

## 0.6.23 (2025-03-31)

- [#163](https://github.com/babashka/process/issues/163), [#164](https://github.com/babashka/process/issues/164): Program resolution strategy for `exec` and Windows now matches macOS/Linux/PowerShell ([@lread](https://github.com/lread))
- Fix memory leak by executing shutdown hook when process finishes earlier than VM exit ([@maxweber](https://github.com/maxweber))

## 0.5.22 (2024-02-29)

- [#123](https://github.com/babashka/process/issues/123): `exec` now converts `:env` and `:extra-env` keywords ([@lread](https://github.com/lread))
- [#140](https://github.com/babashka/process/issues/140): accept `java.nio.file.Path` as `:dir` argument
- [#148](https://github.com/babashka/process/issues/148): accept `Path` in `:out`, `:err` and `:in`
- Support `:out :bytes` ([@hansbugge](https://github.com/hansbugge))

## 0.5.21 (2023-05-18)

- [#126](https://github.com/babashka/process/issues/126): Consider `:dir` when resolving relative `program` on Windows ([@lread](https://github.com/lread))

## 0.5.20 (2023-05-17)

- Bump `babashka.fs` to 0.4.18

## 0.5.19 (2023-05-11)

- [#124](https://github.com/babashka/process/issues/124): Allow `:cmd` to be passed in map argument

## 0.5.18

- Fix regression introduced in [#112](https://github.com/babashka/process/issues/112): `exec` always needs to resolve the full path of the program

## 0.5.17

- [#113](https://github.com/babashka/process/issues/113): Support redirecting stderr to stdout ([@lread](https://github.com/lread))
- [#112](https://github.com/babashka/process/issues/112): Support `:pre-start-fn` in exec

## 0.4.16

- [#100](https://github.com/babashka/process/issues/100): preserve single-quotes in double-quoted string

## 0.4.15

- **BREAKING**: `:write` instead of `:append` to an `:out-file` by default. This
  default was undocumented so the impact should be small.

## 0.4.14

- Auto-load `babashka.process.pprint` if `clojure.pprint` was already loaded

## 0.4.13 (2022-12-04)

- Fix invocation with file argument

## 0.4.12

- [#95](https://github.com/babashka/process/issues/95): Unify arg parsing for `shell` and `process`: the recommended syntax is now: `(process opts? & args)`

## 0.3.11

- Add `alive?` to check if process is still alive ([@grzm](https://github.com/grzm))
- Update `process`' docstring to mention clojure standard streams ([@ikappaki](https://github.com/ikappaki))
- Slurp `:err` in `check` only if it is an input stream ([@ikappaki](https://github.com/ikappaki))

## 0.2.10

- Return `deref-ed` result to `:exit-fn`

## 0.2.9

- Add `:exit-fn` to `process`: a one-argument function which will be called upon the termination of the process.

## 0.1.8

- [#84](https://github.com/babashka/process/issues/84): fix `tokenize` with single-quoted strings inside double-quoted string

## 0.1.7

- [#76](https://github.com/babashka/process/issues/76): error while loading `babashka.process` with older GraalVM libraries

## 0.1.4

- [#72](https://github.com/babashka/process/issues/72): exec `arg0` should be the program name by default
- [#73](https://github.com/babashka/process/issues/73): support for Clojure 1.9

## 0.1.3

- Add `shell` function that behaves similar to `babashka.tasks/shell`
- Support string as value for `:out` and `:err` for writing to file

## 0.1.2

- [#53](https://github.com/babashka/process/issues/53): Added namespace
  `babashka.process.pprint` which defines how to `pprint` a `Process` record (if
  loaded).
- Added `:pre-start-fn`to `process` options, e.g. for printing the command before execution
- Support `exec` but only in GraalVM binaries

## 0.1.1

Similar to `tools.build.api/process`, `process` now supports appending output to
files. To reduce cognitive overhead between libraries, process adopted the same
convention:

- feat [#44](https://github.com/babashka/process/issues/44):
  - Support `:out` + (`:write` / `:append`) and `:out-file` + file
  - Support `:err` + (`:write` / `:append`) and `:err-file` + file

## 0.1.0

- Resolve binaries on Windows using `fs/which`
- String with backslash is tokenized incorrectly [#47](https://github.com/babashka/process/issues/47)
- Support `deref` with timeout [#50](https://github.com/babashka/process/issues/50) ([@SevereOverfl0w](https://github.com/SevereOverfl0w))
- Fix piping with `$` macro [#52](https://github.com/babashka/process/issues/52)

## 0.0.2

- Add tokenization [#39](https://github.com/babashka/process/issues/39). See [docs](https://github.com/babashka/process#tokenization).
- Add `:extra-env` option [#40](https://github.com/babashka/process/issues/40). See [docs](https://github.com/babashka/process#add-environment).

## 0.0.1

Initial release
