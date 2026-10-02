package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func read(t *testing.T, p string) []byte {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestTokens(t *testing.T) {
	out, n := dumpTokens("x.go", []byte("package p // c\nx := 1 + 2\n"))
	if n != 0 {
		t.Fatalf("errors: %d", n)
	}
	want := "0\tPACKAGE\t\n8\tIDENT\tp\n10\tCOMMENT\t// c\n14\tSEMICOLON\t\"\\n\"\n" +
		"15\tIDENT\tx\n17\tDEFINE\t\n20\tINT\t1\n22\tADD\t\n24\tINT\t2\n25\tSEMICOLON\t\"\\n\"\n26\tEOF\t\n"
	if string(out) != want {
		t.Fatalf("got:\n%q\nwant:\n%q", out, want)
	}
}

func TestASTSample(t *testing.T) {
	out, n := dumpAST("sample.go", read(t, "testdata/sample.go"))
	s := string(out)
	if n != 0 {
		t.Fatalf("unexpected errors: %s", s)
	}
	for _, sub := range []string{"(File 0-", "  (Ident 8-14 sample)", "(BinaryExpr ", " GTR)", " ADD)", "(CompositeLit ", " INT \"1\")", "(FuncType ", "(CommentGroup "} {
		if !strings.Contains(s, sub) {
			t.Errorf("missing %q in\n%s", sub, s)
		}
	}
	if strings.Contains(s, "\r") || strings.HasPrefix(s, " ") {
		t.Errorf("bad line endings or indentation")
	}
}

func TestASTErrors(t *testing.T) {
	out, n := dumpAST("broken.go", read(t, "testdata/broken.go"))
	if n == 0 || !strings.Contains(string(out), "\nERROR ") {
		t.Fatalf("expected ERROR lines, got n=%d:\n%s", n, out)
	}
	if !strings.HasPrefix(string(out), "(File ") {
		t.Fatalf("partial AST missing:\n%s", out)
	}
}

func TestWalk(t *testing.T) {
	src, outDir := t.TempDir(), t.TempDir()
	put := func(rel, body string) {
		p := filepath.Join(src, rel)
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte(body), 0o644)
	}
	put("a.go", "package a\n")
	put("sub/b.go", "package b\nfunc {\n")
	put("testdata/c.go", "package c\n")
	put("_skip.go", "package s\n")
	put(".hidden.go", "package h\n")
	var so, se bytes.Buffer
	if code := runWalk([]string{src, "-tokens", "-ast", "-out", outDir}, &so, &se); code != 0 {
		t.Fatalf("exit %d: %s", code, se.String())
	}
	if !strings.Contains(so.String(), "walk: 2 files, 1 with parse errors, 0 failed") {
		t.Fatalf("summary: %s", so.String())
	}
	for _, f := range []string{"a.go.tokens", "a.go.ast", filepath.Join("sub", "b.go.ast")} {
		if _, err := os.Stat(filepath.Join(outDir, f)); err != nil {
			t.Errorf("missing %s", f)
		}
	}
	if _, err := os.Stat(filepath.Join(outDir, "testdata")); err == nil {
		t.Errorf("testdata must be skipped")
	}
}
