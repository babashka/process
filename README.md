# babashka.process

A Clojure wrapper around `java.lang.ProcessBuilder`.

Status: alpha, WIP.

## Example usage

``` clojure
user=> (require '[babashka.process :refer [process]])
```

Invoke `ls` and slurp the output stream:

``` clojure
user=> (-> (process ["ls"]) :out slurp)
"LICENSE\nREADME.md\nsrc\n"
```

Output as string:

``` clojure
user=> (-> (process ["ls"] {:out :string}) :out)
"LICENSE\nREADME.md\nsrc\n"
```

Redirect output to stdout:

``` clojure
user=> (do (-> (process ["ls"] {:out :inherit})) nil)
nil
user=> LICENSE		README.md	src
```

Redirect output stream from one process to inputstream of the next process:

``` clojure
(let [is (-> (process ["ls"]) :out)]
    (process ["cat"] {:in is
                      :out :inherit})
    nil)
nil
user=> LICENSE
README.md
src
```

## License

Copyright © 2019-2020 Michiel Borkent

Distributed under the EPL License. See LICENSE.
