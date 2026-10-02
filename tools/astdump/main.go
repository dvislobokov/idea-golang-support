// Command astdump prints reference dumps of go/scanner tokens and go/ast trees.
//
// Usage:
//
//	astdump tokens <file.go>
//	astdump ast <file.go>
//	astdump walk <dir> [-tokens|-ast] -out <outdir>
package main

import (
	"fmt"
	"os"
)

func usage() {
	fmt.Fprintln(os.Stderr, "usage:\n  astdump tokens <file.go>\n  astdump ast <file.go>\n  astdump walk <dir> [-tokens|-ast] -out <outdir>")
	os.Exit(2)
}

func main() {
	if len(os.Args) < 3 {
		usage()
	}
	switch os.Args[1] {
	case "tokens", "ast":
		if len(os.Args) != 3 {
			usage()
		}
		src, err := os.ReadFile(os.Args[2])
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		var out []byte
		if os.Args[1] == "tokens" {
			out, _ = dumpTokens(os.Args[2], src)
		} else {
			out, _ = dumpAST(os.Args[2], src)
		}
		os.Stdout.Write(out)
	case "walk":
		os.Exit(runWalk(os.Args[2:], os.Stdout, os.Stderr))
	default:
		usage()
	}
}
