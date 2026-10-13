// Command oracle type-checks Go packages with go/packages + go/types and prints every type error as one JSON line:
// file, line, column, the go/types error code name (internal/types/errors) and the message. It is the reference for
// docs/compare/*-broken-*.md: unlike `go build`, it never stops after ten errors and gives each error a code.
//
//	cd tools/compare/oracle && go run . -dir <module dir> [-tests] <patterns...>
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strconv"

	"golang.org/x/tools/go/packages"
)

type record struct {
	Pkg     string `json:"pkg"`
	File    string `json:"file"`
	Line    int    `json:"line"`
	Col     int    `json:"col"`
	Code    string `json:"code"`
	Kind    string `json:"kind"`
	Soft    bool   `json:"soft,omitempty"`
	Message string `json:"msg"`
}

func main() {
	dir := flag.String("dir", ".", "module directory the patterns are relative to")
	tests := flag.Bool("tests", false, "also check test packages")
	flag.Parse()
	names := codeNames()
	cfg := &packages.Config{
		Mode:  packages.NeedName | packages.NeedFiles | packages.NeedSyntax | packages.NeedTypes | packages.NeedTypesInfo | packages.NeedImports,
		Dir:   *dir,
		Tests: *tests,
	}
	pkgs, err := packages.Load(cfg, flag.Args()...)
	if err != nil {
		fmt.Fprintln(os.Stderr, "load:", err)
		os.Exit(2)
	}
	out := json.NewEncoder(os.Stdout)
	seen := map[string]bool{}
	for _, p := range pkgs {
		emit := func(r record) {
			r.Pkg = p.PkgPath
			if r.File != "" {
				if rel, err := filepath.Rel(*dir, r.File); err == nil {
					r.File = filepath.ToSlash(rel)
				}
			}
			key := fmt.Sprintf("%s:%d:%d:%s", r.File, r.Line, r.Col, r.Message)
			if seen[key] {
				return // a package and its test variant report the same error
			}
			seen[key] = true
			_ = out.Encode(r)
		}
		for _, e := range p.TypeErrors {
			pos := e.Fset.Position(e.Pos)
			emit(record{File: pos.Filename, Line: pos.Line, Col: pos.Column, Code: names[code(e)], Kind: "type", Soft: e.Soft, Message: e.Msg})
		}
		for _, e := range p.Errors {
			if e.Kind == packages.TypeError {
				continue // already reported with its code above
			}
			file, line, col := splitPos(e.Pos)
			emit(record{File: file, Line: line, Col: col, Kind: kindName(e.Kind), Message: e.Msg})
		}
	}
}

// code reads types.Error's unexported go116code: the only way to the error code from outside the standard library.
func code(e types.Error) int {
	f := reflect.ValueOf(e).FieldByName("go116code")
	if !f.IsValid() {
		return 0
	}
	return int(f.Int())
}

// codeNames maps the code values to their names by evaluating the iota constants of $GOROOT/src/internal/types/errors/codes.go.
func codeNames() map[int]string {
	names := map[int]string{0: ""}
	path := filepath.Join(runtime.GOROOT(), "src", "internal", "types", "errors", "codes.go")
	f, err := parser.ParseFile(token.NewFileSet(), path, nil, 0)
	if err != nil {
		return names
	}
	for _, decl := range f.Decls {
		gen, ok := decl.(*ast.GenDecl)
		if !ok || gen.Tok != token.CONST {
			continue
		}
		var last ast.Expr
		for i, spec := range gen.Specs {
			vs := spec.(*ast.ValueSpec)
			if len(vs.Values) > 0 {
				last = vs.Values[0]
			}
			value := 0
			switch v := last.(type) {
			case *ast.Ident:
				if v.Name == "iota" {
					value = i
				}
			case *ast.UnaryExpr:
				if lit, ok := v.X.(*ast.BasicLit); ok && v.Op == token.SUB {
					n, _ := strconv.Atoi(lit.Value)
					value = -n
				}
			case *ast.BasicLit:
				value, _ = strconv.Atoi(v.Value)
			}
			for _, n := range vs.Names {
				if n.Name != "_" {
					names[value] = n.Name
				}
			}
		}
	}
	return names
}

func splitPos(pos string) (string, int, int) {
	// file:line:col, where file may contain a drive letter colon
	var line, col int
	for k := 0; k < 2; k++ {
		i := lastColon(pos)
		if i < 0 {
			break
		}
		n, err := strconv.Atoi(pos[i+1:])
		if err != nil {
			break
		}
		if k == 0 {
			col = n
		} else {
			line = n
		}
		pos = pos[:i]
	}
	if line == 0 && col != 0 {
		line, col = col, 0
	}
	return pos, line, col
}

func lastColon(s string) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}

func kindName(k packages.ErrorKind) string {
	switch k {
	case packages.ListError:
		return "list"
	case packages.ParseError:
		return "parse"
	default:
		return "unknown"
	}
}
