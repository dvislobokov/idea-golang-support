package main

import (
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
)

type walkResult struct {
	rel    string
	errs   int
	failed error
}

// runWalk implements `astdump walk <dir> [-tokens|-ast] -out <outdir>`; returns the exit code.
// Directories named testdata or starting with "." or "_" are skipped, as are files starting
// with "." or "_".
func runWalk(args []string, stdout, stderr io.Writer) int {
	var dir, outDir string
	var wantTokens, wantAST bool
	for i := 0; i < len(args); i++ {
		switch a := args[i]; a {
		case "-tokens":
			wantTokens = true
		case "-ast":
			wantAST = true
		case "-out":
			i++
			if i >= len(args) {
				fmt.Fprintln(stderr, "walk: -out needs a value")
				return 2
			}
			outDir = args[i]
		default:
			if strings.HasPrefix(a, "-") || dir != "" {
				fmt.Fprintf(stderr, "walk: unexpected argument %q\n", a)
				return 2
			}
			dir = a
		}
	}
	if dir == "" || outDir == "" {
		fmt.Fprintln(stderr, "usage: astdump walk <dir> [-tokens|-ast] -out <outdir>")
		return 2
	}
	if !wantTokens && !wantAST {
		wantAST = true
	}

	var files []string
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		name := d.Name()
		if d.IsDir() {
			if path != dir && (name == "testdata" || strings.HasPrefix(name, ".") || strings.HasPrefix(name, "_")) {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasSuffix(name, ".go") && !strings.HasPrefix(name, ".") && !strings.HasPrefix(name, "_") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		fmt.Fprintln(stderr, "walk:", err)
		return 1
	}
	sort.Strings(files)

	results := make([]walkResult, len(files))
	jobs := make(chan int)
	var wg sync.WaitGroup
	for w := 0; w < runtime.NumCPU(); w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				results[i] = processFile(dir, files[i], outDir, wantTokens, wantAST)
			}
		}()
	}
	for i := range files {
		jobs <- i
	}
	close(jobs)
	wg.Wait()

	withErrors, failed := 0, 0
	for _, r := range results {
		switch {
		case r.failed != nil:
			failed++
			fmt.Fprintf(stdout, "FAILED %s: %v\n", r.rel, r.failed)
		case r.errs > 0:
			withErrors++
			fmt.Fprintf(stdout, "ERRORS %s (%d)\n", r.rel, r.errs)
		}
	}
	fmt.Fprintf(stdout, "walk: %d files, %d with parse errors, %d failed\n", len(files), withErrors, failed)
	if failed > 0 {
		return 1
	}
	return 0
}

func processFile(root, path, outDir string, wantTokens, wantAST bool) walkResult {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return walkResult{rel: path, failed: err}
	}
	res := walkResult{rel: filepath.ToSlash(rel)}
	src, err := os.ReadFile(path)
	if err != nil {
		res.failed = err
		return res
	}
	write := func(ext string, data []byte, n int) error {
		if n > res.errs {
			res.errs = n
		}
		target := filepath.Join(outDir, rel+ext)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		return os.WriteFile(target, data, 0o644)
	}
	if wantTokens {
		data, n := dumpTokens(res.rel, src)
		if err := write(".tokens", data, n); err != nil {
			res.failed = err
			return res
		}
	}
	if wantAST {
		data, n := dumpAST(res.rel, src)
		if err := write(".ast", data, n); err != nil {
			res.failed = err
		}
	}
	return res
}
