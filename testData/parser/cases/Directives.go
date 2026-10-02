// Exercises compiler directives, which are ordinary line comments to the lexer
// (GRAMMAR.md section K): go:build, go:embed, go:generate, go:noinline and friends.

//go:build (linux && amd64) || !cgo
// +build linux,amd64 !cgo

//go:generate go run gen.go -type=T
//go:generate stringer -type=Kind

package cases

import (
	_ "embed"
)

//go:embed hello.txt
var hello string

//go:embed data/*.json
//go:embed other.bin
var data []byte

//go:noinline
func slow(x int) int { return x * 2 }

//go:nosplit
//go:linkname localname runtime.name
func nosplit() {}

//export Callback
func Callback() {}

type T struct{} //go:notinheap
