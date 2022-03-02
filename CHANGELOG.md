# Changelog

## 0.1.1

- feat [#44](https://github.com/babashka/process/issues/44):
  - Support `:out` + `:write` or `:append` + `:out-file` + file
  - Support `:err` + `:write` or `:append` + `:err-file` + file

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
