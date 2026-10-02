// Command portshorttest extracts the valids/invalids snippets from
// $GOROOT/src/go/parser/short_test.go into testData/parser/short.
package main

import (
	"flag"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

var errRe = regexp.MustCompile(`/\*\s*ERROR(?:\s+HERE)?\s+("(?:[^"\\]|\\.)*"|` + "`[^`]*`" + `)\s*\*/`)

func main() {
	goroot := flag.String("goroot", "", "GOROOT (default: go env GOROOT)")
	out := flag.String("out", filepath.Join("testData", "parser", "short"), "output directory")
	flag.Parse()
	if *goroot == "" {
		b, err := exec.Command("go", "env", "GOROOT").Output()
		if err != nil {
			fatal(err)
		}
		*goroot = strings.TrimSpace(string(b))
	}
	path := filepath.Join(*goroot, "src", "go", "parser", "short_test.go")
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, path, nil, 0)
	if err != nil {
		fatal(err)
	}
	lists := map[string][]string{}
	for _, d := range f.Decls {
		gd, ok := d.(*ast.GenDecl)
		if !ok || gd.Tok != token.VAR {
			continue
		}
		for _, s := range gd.Specs {
			vs := s.(*ast.ValueSpec)
			for i, n := range vs.Names {
				if n.Name != "valids" && n.Name != "invalids" {
					continue
				}
				cl, ok := vs.Values[i].(*ast.CompositeLit)
				if !ok {
					fatal(fmt.Errorf("%s is not a composite literal", n.Name))
				}
				for _, e := range cl.Elts {
					bl, ok := e.(*ast.BasicLit)
					if !ok || bl.Kind != token.STRING {
						fatal(fmt.Errorf("%s: non-string element at %s", n.Name, fset.Position(e.Pos())))
					}
					v, err := strconv.Unquote(bl.Value)
					if err != nil {
						fatal(err)
					}
					lists[n.Name] = append(lists[n.Name], v)
				}
			}
		}
	}
	write(filepath.Join(*out, "valid"), lists["valids"], false)
	write(filepath.Join(*out, "invalid"), lists["invalids"], true)
	fmt.Printf("valid: %d snippets\ninvalid: %d snippets\n", len(lists["valids"]), len(lists["invalids"]))
}

func write(dir string, snippets []string, invalid bool) {
	if err := os.RemoveAll(dir); err != nil {
		fatal(err)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		fatal(err)
	}
	var idx strings.Builder
	for i, s := range snippets {
		id := fmt.Sprintf("%03d", i)
		if err := os.WriteFile(filepath.Join(dir, id+".go"), []byte(s), 0o644); err != nil {
			fatal(err)
		}
		q := strconv.Quote(s)
		if !invalid {
			idx.WriteString(id + "\t" + q + "\n")
			continue
		}
		var msgs []string
		for _, m := range errRe.FindAllStringSubmatch(s, -1) {
			u, err := strconv.Unquote(m[1])
			if err != nil { // e.g. "\[" is not a valid Go escape; keep the text as written
				u = m[1][1 : len(m[1])-1]
			}
			msgs = append(msgs, u)
		}
		idx.WriteString(id + "\t" + strings.Join(msgs, " | ") + "\t" + q + "\n")
	}
	if err := os.WriteFile(filepath.Join(dir, "index.txt"), []byte(idx.String()), 0o644); err != nil {
		fatal(err)
	}
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "portshorttest:", err)
	os.Exit(1)
}
