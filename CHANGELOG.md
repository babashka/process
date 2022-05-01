# Changelog

## Unreleased

- Issue [#53](https://github.com/babashka/process/issues/53):
  - Added namespace `babashka.process.pprint` which defines how to `pprint` a `Process` record (if loaded).
- Part of feature request [#1249](https://github.com/babashka/babashka/issues/1249):
  - Added `:cmd-print-fn` option to `process` options to side-effect command tokens

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
