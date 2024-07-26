# Babashka Process Test Resources

For test coverage, we need executables that emit their path and their current working directory.
For example, on Windows:
```
> .\print-dirs.bat
exepath: Z:\babashka\process\test-resources\print-dirs.bat
workdir: Z:\babashka\process\test-resources
> cd ..
>test-resources\print-dirs
exepath: Z:\babashka\process\test-resources\print-dirs.exe
workdir: Z:\babashka\process
```

This is straightforward for Linux/macOS `.sh`, Windows `.cmd` and Windows `.bat` variants, but for the Windows `.exe` variant, we need to create a binary.

## Generating a Windows .exe

It doesn't matter how `print-dirs.exe` is created; it won't need to be recreated often (we commit it to source control).

Since it generates relatively small binaries very quickly for any architecture, we've built a `print-dirs.exe` using Go.
Source is in [print-dirs.go](print-dirs.go)

### Minimal Build
Presuming you have [Go](https://go.dev/) installed, from this dir:

```shell
GOOS=windows GOARCH=amd64 go build -o print-dirs.exe print-dirs.go
```

For me, this generated a 1.9mb `print-dirs.exe`.

### Build a Smaller Exe
Since this `.exe` is checked into version control, I opted to create a smaller binary by adding the following `-ldflags` to strip debug info:

```shell
GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o print-dirs.exe print-dirs.go
```

For me, the generated `print-dirs.exe` is now 1.3mb.
To further shrink down the binary, you can use [UPX](https://upx.github.io/) (which is also cross-platform):

```
upx --ultra-brute print-dirs.exe
```

And now `print-dirs.exe` is 457kb.

### Building With Docker
If you don't want to install Go and UPX on your dev box but have docker installed, you can build from a docker image like so:

``` shell
docker run --rm \
  -v "$PWD":/src \
  -w /src \
  devopsworks/golang-upx:latest \
  bash -c 'GOOS=windows GOARCH=amd64 \
    go build -ldflags "-s -w" -o print-dirs.exe print-dirs.go &&
    upx --ultra-brute print-dirs.exe'
```

This is ultimately the command I ran to create our Windows .exe.

## What about Windows .com?

These days, `.com` executables are rare.

I could not find an easy way to create one.
Our tests use `print-dirs.exe` copied to `print-dirs.com` to support `.com` test cases.