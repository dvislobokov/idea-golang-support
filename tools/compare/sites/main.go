// Command sites picks the identifier sites of the completion measurement (docs/compare part 2): every identifier use in the given
// files, classified by the kind of place it stands in, then a deterministic stratified sample. One JSON array on stdout.
//
//	cd tools/compare/sites && go run . -dir <module dir> -per-kind 60 -seed 1 file1.go file2.go ...
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"golang.org/x/tools/go/ast/astutil"
	"golang.org/x/tools/go/packages"
)

type site struct {
	ID     int    `json:"id"`
	File   string `json:"file"`
	Line   int    `json:"line"`
	Col    int    `json:"col"`    // 1-based, bytes
	Offset int    `json:"offset"` // bytes
	Ident  string `json:"ident"`
	Kind   string `json:"kind"`
	Sub    string `json:"sub"`
	// unimported-pkg: the byte range of the import line to delete and the import path
	ImportStart int    `json:"importStart,omitempty"`
	ImportEnd   int    `json:"importEnd,omitempty"`
	ImportPath  string `json:"importPath,omitempty"`
}

func main() {
	dir := flag.String("dir", ".", "module directory")
	perKind := flag.Int("per-kind", 60, "sites per kind")
	seed := flag.Int64("seed", 20261013, "sample seed")
	flag.Parse()
	abs, _ := filepath.Abs(*dir)
	want := map[string]bool{}
	pkgDirs := map[string]bool{}
	for _, f := range flag.Args() {
		p := filepath.Join(abs, filepath.FromSlash(f))
		want[p] = true
		pkgDirs["./"+filepath.ToSlash(filepath.Dir(f))] = true
	}
	var patterns []string
	for d := range pkgDirs {
		patterns = append(patterns, d)
	}
	cfg := &packages.Config{Mode: packages.NeedName | packages.NeedFiles | packages.NeedCompiledGoFiles | packages.NeedSyntax | packages.NeedTypes | packages.NeedTypesInfo | packages.NeedImports, Dir: abs}
	pkgs, err := packages.Load(cfg, patterns...)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	byKind := map[string][]site{}
	for _, p := range pkgs {
		for i, f := range p.Syntax {
			name := p.CompiledGoFiles[i]
			if !want[name] {
				continue
			}
			src, _ := os.ReadFile(name)
			rel, _ := filepath.Rel(abs, name)
			for _, s := range collect(p, f, src) {
				s.File = filepath.ToSlash(rel)
				pos := p.Fset.Position(token.Pos(s.Offset) + token.Pos(p.Fset.File(f.Pos()).Base()))
				s.Line, s.Col = pos.Line, pos.Column
				byKind[s.Kind] = append(byKind[s.Kind], s)
			}
		}
	}
	rng := rand.New(rand.NewSource(*seed))
	var out []site
	kinds := make([]string, 0, len(byKind))
	for k := range byKind {
		kinds = append(kinds, k)
	}
	sort.Strings(kinds)
	for _, k := range kinds {
		list := byKind[k]
		sort.Slice(list, func(i, j int) bool { return list[i].File+fmt.Sprint(list[i].Offset) < list[j].File+fmt.Sprint(list[j].Offset) })
		rng.Shuffle(len(list), func(i, j int) { list[i], list[j] = list[j], list[i] })
		// at most 6 sites of one identifier per kind: otherwise `err` and `r` would fill the sample
		taken, count := 0, map[string]int{}
		for _, s := range list {
			if taken >= *perKind {
				break
			}
			if count[s.Ident] >= 6 {
				continue
			}
			count[s.Ident]++
			out = append(out, s)
			taken++
		}
		fmt.Fprintf(os.Stderr, "%s: %d candidates, %d taken\n", k, len(list), taken)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].File != out[j].File {
			return out[i].File < out[j].File
		}
		return out[i].Offset < out[j].Offset
	})
	for i := range out {
		out[i].ID = i + 1
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", " ")
	_ = enc.Encode(out)
}

func collect(p *packages.Package, f *ast.File, src []byte) []site {
	base := p.Fset.File(f.Pos()).Base()
	off := func(pos token.Pos) int { return int(pos) - base }
	info := p.TypesInfo
	parents = map[ast.Node]ast.Node{}
	astutil.Apply(f, func(c *astutil.Cursor) bool {
		if c.Node() != nil && c.Parent() != nil {
			parents[c.Node()] = c.Parent()
		}
		return true
	}, nil)
	var out []site
	seenImport := map[string]bool{}
	add := func(id *ast.Ident, kind, sub string) {
		if id.Name == "_" {
			return
		}
		out = append(out, site{Offset: off(id.Pos()), Ident: id.Name, Kind: kind, Sub: sub})
	}
	astutil.Apply(f, func(c *astutil.Cursor) bool {
		id, ok := c.Node().(*ast.Ident)
		if !ok {
			return true
		}
		obj := info.Uses[id]
		if obj == nil {
			return true // declarations and blank / unresolved names are not completion sites
		}
		parent := c.Parent()
		switch par := parent.(type) {
		case *ast.SelectorExpr:
			if c.Name() == "Sel" {
				sub := "value"
				if x, ok := par.X.(*ast.Ident); ok {
					if _, isPkg := info.Uses[x].(*types.PkgName); isPkg {
						sub = "package-member"
					}
				}
				if sub == "value" {
					switch o := obj.(type) {
					case *types.Var:
						if o.IsField() {
							sub = "field"
						}
					case *types.Func:
						sub = "method"
					}
				}
				add(id, "after-dot", sub)
				return true
			}
			if pn, isPkg := obj.(*types.PkgName); isPkg && !seenImport[pn.Imported().Path()] {
				// the first use of an import in the file: the import is deleted, the package name is to be completed with its import
				for _, spec := range f.Imports {
					if strings.Trim(spec.Path.Value, "\"") == pn.Imported().Path() && spec.Name == nil {
						start, end := lineRange(src, off(spec.Pos()), off(spec.End()))
						if len(f.Imports) == 1 {
							break // a lone import declaration: deleting the line leaves `import (` or nothing sane
						}
						seenImport[pn.Imported().Path()] = true
						s := site{Offset: off(id.Pos()), Ident: id.Name, Kind: "unimported-package", Sub: stdOrModule(pn.Imported().Path()), ImportStart: start, ImportEnd: end, ImportPath: pn.Imported().Path()}
						out = append(out, s)
					}
				}
				return true
			}
		case *ast.KeyValueExpr:
			if c.Name() == "Key" {
				if v, ok := obj.(*types.Var); ok && v.IsField() {
					add(id, "struct-literal-field", "key")
					return true
				}
			}
		case *ast.CaseClause:
			if c.Name() == "List" {
				if _, ok := obj.(*types.TypeName); ok {
					add(id, "case", "type-switch")
				} else if _, ok := obj.(*types.Const); ok {
					add(id, "case", "const")
				} else {
					add(id, "case", "value")
				}
				return true
			}
		}
		if _, ok := obj.(*types.TypeName); ok {
			add(id, "type", typeSub(c))
			return true
		}
		if stmtStart(c, id) {
			add(id, "statement-start", objKind(obj))
			return true
		}
		if sub := exprPlace(c, id); sub != "" {
			add(id, "expression", sub)
		}
		return true
	}, nil)
	return out
}

func stdOrModule(path string) string {
	if strings.Contains(strings.SplitN(path, "/", 2)[0], ".") {
		return "module"
	}
	return "std"
}

// lineRange widens [start, end) to whole lines, the newline included.
func lineRange(src []byte, start, end int) (int, int) {
	for start > 0 && src[start-1] != '\n' {
		start--
	}
	for end < len(src) && src[end] != '\n' {
		end++
	}
	if end < len(src) {
		end++
	}
	return start, end
}

func objKind(obj types.Object) string {
	switch obj.(type) {
	case *types.Func:
		return "func"
	case *types.Var:
		return "var"
	case *types.PkgName:
		return "package"
	case *types.Builtin:
		return "builtin"
	case *types.Const:
		return "const"
	}
	return "other"
}

// stmtStart: the identifier is the first token of a statement in a block (a call, an assignment to an existing name, ...).
func stmtStart(c *astutil.Cursor, id *ast.Ident) bool {
	// walk up through the leftmost operands: `x.f()`, `x[i] = ...`, `x++`
	n := ast.Node(id)
	for cur := c.Parent(); cur != nil; {
		switch s := cur.(type) {
		case ast.Stmt:
			_, inBlock := blockParent(s)
			return inBlock && s.Pos() == id.Pos()
		default:
			if cur.Pos() != n.Pos() {
				return false
			}
		}
		n = cur
		cur = parentOf(c, cur)
		if cur == nil {
			return false
		}
	}
	return false
}

// exprPlace: the identifier is (the start of) a call argument, an assignment right-hand side or a return result.
func exprPlace(c *astutil.Cursor, id *ast.Ident) string {
	switch par := c.Parent().(type) {
	case *ast.CallExpr:
		if c.Name() == "Args" {
			return "call-argument"
		}
	case *ast.AssignStmt:
		if c.Name() == "Rhs" {
			return "assignment-rhs"
		}
	case *ast.ValueSpec:
		if c.Name() == "Values" {
			return "assignment-rhs"
		}
	case *ast.ReturnStmt:
		if c.Name() == "Results" {
			return "return"
		}
	case *ast.SelectorExpr:
		// `pkg.F` / `x.f` as an argument: the identifier starts the expression
		_ = par
	}
	return ""
}

func typeSub(c *astutil.Cursor) string {
	for i, n := 0, ast.Node(c.Parent()); n != nil && i < 4; i++ {
		switch n.(type) {
		case *ast.Field:
			return "field-or-param"
		case *ast.ValueSpec:
			return "var"
		case *ast.IndexExpr, *ast.IndexListExpr:
			return "type-argument"
		case *ast.CompositeLit:
			return "composite-literal"
		case *ast.CallExpr:
			return "conversion"
		case *ast.TypeSpec:
			return "type-decl"
		}
		n = parentOf(c, n)
	}
	return "other"
}

// parents of the nodes of the file being collected: astutil.Cursor gives only the immediate parent.
var parents = map[ast.Node]ast.Node{}

func parentOf(c *astutil.Cursor, n ast.Node) ast.Node { return parents[n] }

func blockParent(s ast.Stmt) (ast.Node, bool) {
	p := parents[s]
	switch p.(type) {
	case *ast.BlockStmt, *ast.CaseClause, *ast.CommClause:
		return p, true
	}
	return p, false
}

