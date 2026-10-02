package main

import (
	"bytes"
	"fmt"
	"go/ast"
	"go/parser"
	"go/scanner"
	"go/token"
	"strconv"
	"strings"
)

// dumpAST parses src and dumps the (possibly partial) AST. It returns the dump and the
// number of syntax errors, which are appended as ERROR lines.
func dumpAST(name string, src []byte) ([]byte, int) {
	fset := token.NewFileSet()
	f, err := parser.ParseFile(fset, name, src, parser.ParseComments|parser.SkipObjectResolution)
	var buf bytes.Buffer
	if f != nil {
		if file := fset.File(f.FileStart); file != nil {
			d := &dumper{buf: &buf, file: file}
			d.dump(f)
		}
	}
	nerr := 0
	if err != nil {
		if list, ok := err.(scanner.ErrorList); ok {
			for _, e := range list {
				fmt.Fprintf(&buf, "ERROR %d:%d %s\n", e.Pos.Line, e.Pos.Column, oneLine(e.Msg))
			}
			nerr = len(list)
		} else {
			fmt.Fprintf(&buf, "ERROR 0:0 %s\n", oneLine(err.Error()))
			nerr = 1
		}
	}
	return buf.Bytes(), nerr
}

type dumper struct {
	buf  *bytes.Buffer
	file *token.File
}

// off converts a position to a byte offset; invalid positions give -1 and
// positions past EOF (possible in partial ASTs) are clamped to the file size.
func (d *dumper) off(p token.Pos) int {
	if !p.IsValid() {
		return -1
	}
	o := int(p) - d.file.Base()
	if o < 0 {
		return -1
	}
	if o > d.file.Size() {
		o = d.file.Size()
	}
	return o
}

func (d *dumper) line(depth int, n ast.Node, extra string) {
	d.buf.WriteString(strings.Repeat("  ", depth))
	fmt.Fprintf(d.buf, "(%s %d-%d%s)\n", nodeType(n), d.off(n.Pos()), d.off(n.End()), extra)
}

func (d *dumper) dump(f *ast.File) {
	depth := -1
	ast.Inspect(f, func(n ast.Node) bool {
		if n == nil {
			depth--
			return true
		}
		switch n.(type) {
		case *ast.CommentGroup, *ast.Comment:
			return false // dumped separately at the end
		}
		depth++
		d.line(depth, n, extraFields(n))
		return true
	})
	for _, g := range f.Comments {
		d.line(0, g, "")
		for _, c := range g.List {
			d.line(1, c, "")
		}
	}
}

func nodeType(n ast.Node) string {
	return strings.TrimPrefix(fmt.Sprintf("%T", n), "*ast.")
}

func extraFields(n ast.Node) string {
	switch n := n.(type) {
	case *ast.Ident:
		return " " + n.Name
	case *ast.BasicLit:
		return " " + n.Kind.String() + " " + strconv.Quote(n.Value)
	case *ast.BinaryExpr:
		return " " + tokenName(n.Op)
	case *ast.UnaryExpr:
		return " " + tokenName(n.Op)
	case *ast.AssignStmt:
		return " " + tokenName(n.Tok)
	case *ast.IncDecStmt:
		return " " + tokenName(n.Tok)
	case *ast.GenDecl:
		return " " + tokenName(n.Tok)
	case *ast.BranchStmt:
		return " " + tokenName(n.Tok)
	case *ast.RangeStmt:
		if n.Tok != token.ILLEGAL {
			return " " + tokenName(n.Tok)
		}
	case *ast.ChanType:
		return fmt.Sprintf(" dir=%d", n.Dir)
	case *ast.SliceExpr:
		if n.Slice3 {
			return " 3"
		}
	}
	return ""
}
