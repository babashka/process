package main

import (
    "fmt"
    "os"
)

func main() {
    executable, _ := os.Executable()
    fmt.Fprintln(os.Stdout,"exepath:", executable)
    cwd, _ := os.Getwd()
    fmt.Fprintln(os.Stdout,"workdir:", cwd)
}
