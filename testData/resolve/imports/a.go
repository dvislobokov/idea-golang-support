package imports

import (
	/*def*/ "fmt"
	/*def:str*/ str "strings"
	. "errors"
	_ "os"
	/*def*/ "go/ast"
)

func f() {
	/*ref*/ fmt./*ref GOROOT:fmt/print.go*/ Println(/*ref:str*/ str./*ref GOROOT:strings/strings.go*/ ToUpper("x"))
	_ = /*ref GOROOT:errors/errors.go*/ New("e")
	_ = /*no ref*/ os
	var n /*ref*/ ast./*ref GOROOT:go/ast/ast.go*/ Node
	_ = n
	_ = fmt./*no ref*/ println
}
