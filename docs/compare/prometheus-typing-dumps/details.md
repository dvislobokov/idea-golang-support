Plugin: plugin; GoLand: goland 2026.2.3

| id | category | scenario | GoLand | plugin | verdict |
|---|---|---|---|---|---|
| E01 | enter | Enter between the braces of an if | `<caret>` | `<caret>` | ok |
| E02 | enter | '{' then Enter after a range header | `<caret>` | `<caret>` | ok |
| E03 | enter | Enter in the middle of an expression | `<caret>seriesAdded` | `<caret>seriesAdded` | ok |
| E04 | enter | Enter inside a line comment | `//<caret>appended per scrape.` | L5: `// <caret>appended per scrape.` | иначе |
| E05 | enter | Enter inside an interpreted string | `"<caret>reload failed")` | L6: `return errors.New("scrape pool` | иначе |
| E06 | enter | Enter inside a raw string | `<caret>rate(http_requests_total[5m]))`` | `<caret>rate(http_requests_total[5m]))`` | ok |
| E07 | enter | Enter after a comma in a struct literal | `<caret>ScrapeInterval: 15 * time.Second}` | `<caret>ScrapeInterval: 15 * time.Second}` | ok |
| E08 | enter | Enter after a comma in an argument list | `<caret>"err", err)` | `<caret>"err", err)` | ok |
| E10 | enter | Enter after a case clause | `<caret>` | `<caret>` | ok |
| C01 | complete | Complete Statement after `if err != nil` | `<caret>` | `<caret>` | ok |
| C02 | complete | Complete Statement after a for header | `<caret>` | `<caret>` | ok |
| C03 | complete | Complete Statement on `func f(` without `)` | `<caret>` | L7: `func (sp *scrapePool) sync(targets []*Target) {` | иначе |
| C04 | complete | Complete Statement on a call without `)` | `<caret>` | `<caret>` | ok |
| C05 | complete | Complete Statement on `return fmt.Errorf(` without `)` | `<caret>` | `<caret>` | ok |
| P01 | pairs | '(' after a method name | `sp.reload(<caret>)` | `sp.reload(<caret>)` | ok |
| P02 | pairs | '"' opens a pair, the closing one is overtyped | `name := "nod<caret>"; name := "node<caret>"; name := "node"<caret>` | `name := "nod<caret>"; name := "node<caret>"; name := "node"<caret>` | ok |
| P03 | pairs | backtick struct tag with quotes inside | `ScrapeInterval time.Duration `yaml:"scrape_interval<caret>"`; ScrapeInterval time.Duration `yaml:"scrape_int… | L6: `ScrapeInterval time.Duration `yaml:""scrape_interval"`<caret>"`` | иначе |
| P04 | pairs | '[' and '{' of a slice literal | `ls := []string{"job<caret>"}; ls := []string{"job"<caret>}; ls := []string{"job"}<caret>` | `ls := []string{"job<caret>"}; ls := []string{"job"<caret>}; ls := []string{"job"}<caret>` | ok |
| P05 | pairs | rune quote | `s := '<caret>'; s := ',<caret>'; s := ','<caret>` | `s := '<caret>'; s := ',<caret>'; s := ','<caret>` | ok |
| P06 | pairs | Backspace on an empty () | `sp.reload<caret>` | `sp.reload<caret>` | ok |
| P07 | pairs | Backspace on an empty "" | `s := <caret>` | `s := <caret>` | ok |
| P08 | pairs | Backspace on an empty {} of a map literal | `m := map[string]string<caret>` | `m := map[string]string<caret>` | ok |
| P09 | pairs | ')' overtypes the existing closing parenthesis | `apply(cfg)<caret>` | `apply(cfg)<caret>` | ok |
| P10 | pairs | ':' and '=' after a name | `n := 1<caret>` | `n := 1<caret>` | ok |
| I01 | indent | paste of unindented lines into a function body | `<caret>` | L3: `import "errors"` | иначе |
| I02 | indent | paste of 4-space lines into a nested block | `<caret>` | `<caret>` | ok |
| I03 | indent | paste of a fragment in the middle of a line | `return interval - 500*time.Millisecond<caret>` | `return interval - 500*time.Millisecond<caret>` | ok |
| I04 | indent | paste of a top-level function with indentation | `<caret>` | `<caret>` | ok |
| I05 | indent | '}' on its own line dedents | `}<caret>` | `}<caret>` | ok |
| I06 | indent | '}' closing a badly spaced one-line block | `if total>0 {   total=1   }<caret>` | `if total>0 {   total=1   }<caret>` | ok |
| F01 | format | Reformat Code on operators and braces | `<caret>total = total + 1` | L3: `func next(total int, limit int) int {` | иначе (GoLand ≠ gofmt, plugin = gofmt) |
| F02 | format | Reformat Code aligns struct fields, tags and comments | `<caret>JobName        string        `yaml:"job_name"` // the job` | `<caret>JobName        string        `yaml:"job_name"` // the job` | ok (GoLand = gofmt, plugin = gofmt) |
| F03 | format | Reformat Code aligns a const block | `<caret>HealthUnknown TargetHealth = "unknown"` | `<caret>HealthUnknown TargetHealth = "unknown"` | ok (GoLand = gofmt, plugin = gofmt) |
| F04 | format | Reformat Code on a map literal and spaces for indentation | `<caret>return m` | `<caret>return m` | ok (GoLand = gofmt, plugin = gofmt) |
| F06 | format | save of a badly formatted file (actions on save) | `total = total + 1*2<caret>` | `total = total + 1*2<caret>` | ok (GoLand ≠ gofmt, plugin ≠ gofmt) |
| S01 | select | Ctrl+W from an identifier inside a call in an if condition | `<selection>→if c.forEach<caret>Stale(func(s string) bool { return len(s) > 0 }) { [→if c.forEachStale(func(s… | `<selection>→if c.forEach<caret>Stale(func(s string) bool { return len(s) > 0 }) { [→if c.forEachStale(func(s… | ok |
| S02 | select | Ctrl+W inside a string literal | `return errors.New(<selection>"scrape pool re<caret>load failed"</selection>) ["scrape pool reload failed"]; … | L6: `<selection>return errors.New("scrape pool re<caret>load failed")</selection>` | иначе |
| S03 | select | Ctrl+W inside a struct tag | `ScrapeInterval string <selection>`yaml:"scrape_in<caret>terval,omitempty" json:"interval"`</selection> [`yam… | L3: `type GlobalConfig <selection>struct {` | иначе |
| S04 | select | Ctrl+W four times, then Ctrl+Shift+W twice | `return float64(added) / <selection>float64(to<caret>tal+1)</selection> [float64(total+1)]; return float64(ad… | `return float64(added) / <selection>float64(to<caret>tal+1)</selection> [float64(total+1)]; return float64(ad… | ok |
| K01 | comment | Ctrl+/ on one line (caret in the middle) | `return n<caret>` | L5: `// n += load()` | иначе |
| K02 | comment | Ctrl+/ on three selected lines | `<caret></selection>→return n` | L5: `<selection>→// n += 2` | иначе |
| K03 | comment | Ctrl+/ uncomments selected lines | `n *= 3<caret></selection>` | `n *= 3<caret></selection>` | ok |
| K04 | comment | Ctrl+Shift+/ on a selected expression | `return total + <selection>/*step*2<caret>*/</selection>` | `return total + <selection>/*step*2<caret>*/</selection>` | ok |
| L01 | lines | Duplicate Line | `ls = append(ls, "j<caret>ob")` | `ls = append(ls, "j<caret>ob")` | ok |
| L03 | lines | Move Line Up into the block above | `n <caret>+= 2` | `n <caret>+= 2` | ok |
| L04 | lines | Move Statement Up past an if block | `n <caret>+= 2` | L5: `n <caret>+= 2` | иначе |
| L05 | lines | Move Statement Down of a whole function | `func fi<caret>rst() int {` | `func fi<caret>rst() int {` | ok |
| O01 | imports | Optimize Imports removes unused imports | `<caret>return fmt.Errorf("timeout %v", time.Second)` | `<caret>return fmt.Errorf("timeout %v", time.Second)` | ok |
| O02 | imports | Optimize Imports with unsorted, aliased and blank imports | `<caret>stdlog.Println(time.Now())` | `<caret>stdlog.Println(time.Now())` | ok |
| O03 | imports | Optimize Imports leaves one import of a group | `<caret>fmt.Println()` | `<caret>fmt.Println()` | ok |
| W01 | surround | items offered for two selected statements, apply 'if' | items: if { statements }, if { statements } else { }, for { statements }, { statements } → `if <caret>{` | L5: `if <caret> {` | иначе |
| W02 | surround | items offered for a selected expression, apply '(expr)' | items: (expression) → `return (added + total)<caret>` | items: (expr) → `return (added + total)<caret>` | иначе |
| W03 | surround | items offered with the caret on a statement (no selection) | if { statements }; if { statements } else { }; for { statements }; { statements } | if; if / else; for; func() { ... }(); go func() { ... }(); defer func() { ... }(); { ... } | иначе |
| G01 | generate | items with the caret inside a struct type | Constructor; Getter and Setter; Getter; Setter; Struct Fields from JSON; Implement Methods…; Empty test file;… | Implement Methods…; Copyright; Constructor...; Method...; Getters...; Setters...; Getters and Setters...; Str… | иначе |
| G02 | generate | items at the top level of a file | Type from JSON; Implement Methods…; Empty test file; Copyright | Copyright; Tests for Package...; Type from JSON... | иначе |
| G03 | generate | items inside a function body | Implement Methods…; Empty test file; Test for function; Tests for file; Tests for package; Copyright | Test…; Copyright; Test; Tests for Package...; Type from JSON... | иначе |
| N01 | nav | Ctrl+B on a local variable | a.go:4: seriesAdded := added * 2 | a.go:4: seriesAdded := added * 2 | ok |
| N02 | nav | Ctrl+B on a method declared in another file of the package | pool.go:6: func (sp *scrapePool) reload(targets []string) { | pool.go:6: func (sp *scrapePool) reload(targets []string) { | ok |
| N03 | nav | Ctrl+B on a standard library function | $GOROOT/src/strings/strings.go:501: func HasPrefix(s, prefix string) bool { | $GOROOT/src/strings/strings.go:501: func HasPrefix(s, prefix string) bool { | ok |
| N04 | nav | Ctrl+B on an import path | dir:$GOROOT/src/net/http | dir:$GOROOT/src/net/http | ok |
| N06 | nav | Ctrl+B on an embedded field in a selector | a.go:6: sync.RWMutex | a.go:6: sync.RWMutex | ok |
| N07 | nav | Ctrl+B on a method promoted from an embedded field | $GOROOT/src/sync/rwmutex.go:114: func (rw *RWMutex) RUnlock() { | $GOROOT/src/sync/rwmutex.go:114: func (rw *RWMutex) RUnlock() { | ok |
| N09 | nav | Ctrl+Alt+B on an interface type | impl.go:3: type memAppender struct{}; impl.go:8: type nopAppender struct{} | impl.go:3: type memAppender struct{}; impl.go:8: type nopAppender struct{} | ok |
| N10 | nav | Ctrl+Alt+B on an interface method at a call site | a.go:5: Commit() error; impl.go:11: func (*nopAppender) Commit() error                                       … | impl.go:11: func (*nopAppender) Commit() error                                          { return nil }; impl.… | иначе |
| N11 | nav | Ctrl+U from an implementing method to the interface method | ERROR JavaException: java.lang.IllegalStateException: Access to Editor models (caret, selection, etc.) is all… | api.go:6: Commit() error | — (GoLand error) |
| N12 | nav | Alt+F7 on a method used in two files | count: 3; a.go:10: sp.reload(nil); loop.go:4: sp.reload(nil); loop.go:6: sp.reload([]string{"a"}) | count: 3; a.go:10: sp.reload(nil); loop.go:4: sp.reload(nil); loop.go:6: sp.reload([]string{"a"}) | ok |
| N13 | nav | Alt+F7 on a struct field | count: 3; a.go:11: t.health = h; a.go:12: _ = Target{health: h}; a.go:8: func (t *Target) up() bool { return … | count: 3; a.go:11: t.health = h; a.go:12: _ = Target{health: h}; a.go:8: func (t *Target) up() bool { return … | ok |
| N14 | nav | Ctrl+Shift+F7 on a local variable (reads and writes) | 4:2-4:7 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 6:3-6:8 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 8:5-8:10 'tot… | 4:2-4:7 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 6:3-6:8 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 8:5-8:10 'tot… | ok |
| N15 | nav | Ctrl+Shift+F7 on `return` (exit points) | 5:1-5:5 'func' SEARCH_RESULT_ATTRIBUTES; 7:3-7:35 'return 0, errors.New("negative")' SEARCH_RESULT_ATTRIBUTES… | 7:3-7:35 'return 0, errors.New("negative")' SEARCH_RESULT_ATTRIBUTES; 10:3-10:21 'panic("too large")' SEARCH_… | иначе |
| N16 | nav | Ctrl+F12 file structure | TargetHealth: string; Scraper;   Scrape(ctx context.Context) error; Target;   Health() TargetHealth;   Scrape… | TargetHealth; HealthUnknown TargetHealth; HealthGood TargetHealth; defaultHealth; Scraper;   Scrape(ctx conte… | иначе |
| H01 | hints | Ctrl+P on the second argument of a method call | start time.Time, [[duration time.Duration]], scraped int, added int, scrapeErr error | start Time, [[duration Duration]], scraped int, added int, scrapeErr error | иначе |
| H02 | hints | Ctrl+P on a variadic argument of fmt.Errorf | format string, [[a ...any]] | format string, [[a ...any]] | ok |
| H03 | hints | Ctrl+P on a generic function call | items []T, [[f func(T) U]] | [T any, U any] items []T, [[f func(T) U]] | иначе |
| H04 | hints | Ctrl+P on a standard library call | s string, [[count int]] | s string, [[count int]] | ok |
| H06 | hints | Ctrl+Q on a standard library function | Package: strings func TrimPrefix(s string, prefix string) string TrimPrefix returns s without the provided le… | func TrimPrefix(s string, prefix string) string TrimPrefix returns s without the provided leading prefix stri… | иначе |
| H07 | hints | Ctrl+Q on a project function with a doc comment | Package: scrape func jitter(interval time.Duration, hash uint64) time.Duration jitter returns a deterministic… | func jitter(interval Duration, hash uint64) Duration jitter returns a deterministic offset in [0, interval) f… | иначе |
| H08 | hints | Ctrl+Q on a struct type | Package: scrape type Target struct { mtx sync.RWMutex labels map[string]string health string } Target refers … | type Target struct { mtx sync.RWMutex labels map[string]string health string } Target refers to a singular HT… | иначе |
| H09 | hints | Ctrl+Shift+P on an index expression | "job" -> string; lset["job"] -> []string | <no expression type> | нет |
| H11 | hints | inlay hints in a function with literal arguments | 5:6 above_line com.intellij.codeInsight.codeVision.ui.renderers.BlockCodeVisionInlayRenderer@564e2eb4; 5:25 a… | 14:4 inline : *scrapeLoop; 14:32 inline limit:; 14:38 inline honorLabels:; 14:44 inline name:; 15:24 inline d… | иначе |
| H12 | hints | inlay hints of an unkeyed struct literal and constants | 3:6 above_line com.intellij.codeInsight.codeVision.ui.renderers.BlockCodeVisionInlayRenderer@5a0719f3; 3:21 a… | 3:21 after_line_end 16 bytes; 9:24 inline = 1024; 10:33 inline = 2048; 14:19 inline T:; 14:25 inline V:; 14:3… | иначе |

ok: 51, иначе: 27, нет: 1, —: 1

### E04 Enter inside a line comment

```diff
--- GoLand
+++ plugin
@@ -3,5 +3,5 @@
 func appendLimit(limit int) int {
 	// appendLimit bounds the number of samples
-	//<caret>appended per scrape.
+	// <caret>appended per scrape.
 	return limit
 }
```

### E05 Enter inside an interpreted string

```diff
--- GoLand
+++ plugin
@@ -4,6 +4,6 @@
 
 func reloadErr() error {
-	return errors.New("scrape pool " +
-		"<caret>reload failed")
+	return errors.New("scrape pool 
+		<caret>reload failed")
 }
 
```

### C03 Complete Statement on `func f(` without `)`

```diff
--- GoLand
+++ plugin
@@ -5,5 +5,5 @@
 type Target struct{}
 
-func (sp *scrapePool) sync(targets []*Target {
+func (sp *scrapePool) sync(targets []*Target) {
 	<caret>
 }
```

### P03 backtick struct tag with quotes inside

```diff
--- GoLand
+++ plugin
@@ -4,5 +4,5 @@
 
 type GlobalConfig struct {
-	ScrapeInterval time.Duration `yaml:"scrape_interval"`<caret>
+	ScrapeInterval time.Duration `yaml:""scrape_interval"`<caret>"`
 }
 
```
GoLand:
```
{
 "steps": [
  {
   "key": "`",
   "line": "\tScrapeInterval time.Duration `<caret>`",
   "selection": null
  },
  {
   "key": "y",
   "line": "\tScrapeInterval time.Duration `y<caret>`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `ya<caret>`",
   "selection": null
  },
  {
   "key": "m",
   "line": "\tScrapeInterval time.Duration `yam<caret>`",
   "selection": null
  },
  {
   "key": "l",
   "line": "\tScrapeInterval time.Duration `yaml<caret>`",
   "selection": null
  },
  {
   "key": ":",
   "line": "\tScrapeInterval time.Duration `yaml:<caret>`",
   "selection": null
  },
  {
   "key": "\"",
   "line": "\tScrapeInterval time.Duration `yaml:\"<caret>\"`",
   "selection": null
  },
  {
   "key": "s",
   "line": "\tScrapeInterval time.Duration `yaml:\"s<caret>\"`",
   "selection": null
  },
  {
   "key": "c",
   "line": "\tScrapeInterval time.Duration `yaml:\"sc<caret>\"`",
   "selection": null
  },
  {
   "key": "r",
   "line": "\tScrapeInterval time.Duration `yaml:\"scr<caret>\"`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `yaml:\"scra<caret>\"`",
   "selection": null
  },
  {
   "key": "p",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrap<caret>\"`",
   "selection": null
  },
  {
   "key": "e",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape<caret>\"`",
   "selection": null
  },
  {
   "key": "_",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_<caret>\"`",
   "selection": null
  },
  {
   "key": "i",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_i<caret>\"`",
   "selection": null
  },
  {
   "key": "n",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_in<caret>\"`",
   "selection": null
  },
  {
   "key": "t",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_int<caret>\"`",
   "selection": null
  },
  {
   "key": "e",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_inte<caret>\"`",
   "selection": null
  },
  {
   "key": "r",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_inter<caret>\"`",
   "selection": null
  },
  {
   "key": "v",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_interv<caret>\"`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_interva<caret>\"`",
   "selection": null
  },
  {
   "key": "l",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_interval<caret>\"`",
   "selection": null
  },
  {
   "key": "\"",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_interval\"<caret>`",
   "selection": null
  },
  {
   "key": "`",
   "line": "\tScrapeInterval time.Duration `yaml:\"scrape_interval\"`<caret>",
   "selection": null
  }
 ]
}
```
plugin:
```
{
 "steps": [
  {
   "key": "`",
   "line": "\tScrapeInterval time.Duration `<caret>`",
   "selection": null
  },
  {
   "key": "y",
   "line": "\tScrapeInterval time.Duration `y<caret>`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `ya<caret>`",
   "selection": null
  },
  {
   "key": "m",
   "line": "\tScrapeInterval time.Duration `yam<caret>`",
   "selection": null
  },
  {
   "key": "l",
   "line": "\tScrapeInterval time.Duration `yaml<caret>`",
   "selection": null
  },
  {
   "key": ":",
   "line": "\tScrapeInterval time.Duration `yaml:\"<caret>\"`",
   "selection": null
  },
  {
   "key": "\"",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"<caret>\"`",
   "selection": null
  },
  {
   "key": "s",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"s<caret>\"`",
   "selection": null
  },
  {
   "key": "c",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"sc<caret>\"`",
   "selection": null
  },
  {
   "key": "r",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scr<caret>\"`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scra<caret>\"`",
   "selection": null
  },
  {
   "key": "p",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrap<caret>\"`",
   "selection": null
  },
  {
   "key": "e",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape<caret>\"`",
   "selection": null
  },
  {
   "key": "_",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_<caret>\"`",
   "selection": null
  },
  {
   "key": "i",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_i<caret>\"`",
   "selection": null
  },
  {
   "key": "n",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_in<caret>\"`",
   "selection": null
  },
  {
   "key": "t",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_int<caret>\"`",
   "selection": null
  },
  {
   "key": "e",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_inte<caret>\"`",
   "selection": null
  },
  {
   "key": "r",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_inter<caret>\"`",
   "selection": null
  },
  {
   "key": "v",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_interv<caret>\"`",
   "selection": null
  },
  {
   "key": "a",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_interva<caret>\"`",
   "selection": null
  },
  {
   "key": "l",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_interval<caret>\"`",
   "selection": null
  },
  {
   "key": "\"",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_interval\"<caret>\"`",
   "selection": null
  },
  {
   "key": "`",
   "line": "\tScrapeInterval time.Duration `yaml:\"\"scrape_interval\"`<caret>\"`",
   "selection": null
  }
 ]
}
```

### I01 paste of unindented lines into a function body

```diff
--- GoLand
+++ plugin
@@ -1,3 +1,5 @@
 package scrape
+
+import "errors"
 
 func validate(limit int) error {
```

### F01 Reformat Code on operators and braces

note: Ctrl+Alt+L. Compare with the gofmt field of the scenario JSON.
```diff
--- GoLand
+++ plugin
@@ -1,5 +1,5 @@
 package scrape
 
-func next(total, limit int) int {
+func next(total int, limit int) int {
 	<caret>total = total + 1
 	if total > limit {
```
gofmt:
```go
package scrape

func next(total int, limit int) int {
	<caret>total = total + 1
	if total > limit {
		return limit
	}
	return total
}
```

### S02 Ctrl+W inside a string literal

```diff
--- GoLand
+++ plugin
@@ -4,5 +4,5 @@
 
 func reloadErr() error {
-	return <selection>errors.New("scrape pool re<caret>load failed")</selection>
+	<selection>return errors.New("scrape pool re<caret>load failed")</selection>
 }
 
```
GoLand:
```
{
 "steps": [
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New(\"scrape pool <selection>re<caret>load</selection> failed\")",
   "selection": "reload"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New(\"<selection>scrape pool re<caret>load failed</selection>\")",
   "selection": "scrape pool reload failed"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New(<selection>\"scrape pool re<caret>load failed\"</selection>)",
   "selection": "\"scrape pool reload failed\""
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New<selection>(\"scrape pool re<caret>load failed\")</selection>",
   "selection": "(\"scrape pool reload failed\")"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn <selection>errors.New(\"scrape pool re<caret>load failed\")</selection>",
   "selection": "errors.New(\"scrape pool reload failed\")"
  }
 ]
}
```
plugin:
```
{
 "steps": [
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New(\"scrape pool <selection>re<caret>load</selection> failed\")",
   "selection": "reload"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New(<selection>\"scrape pool re<caret>load failed\"</selection>)",
   "selection": "\"scrape pool reload failed\""
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn errors.New<selection>(\"scrape pool re<caret>load failed\")</selection>",
   "selection": "(\"scrape pool reload failed\")"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\treturn <selection>errors.New(\"scrape pool re<caret>load failed\")</selection>",
   "selection": "errors.New(\"scrape pool reload failed\")"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\t<selection>return errors.New(\"scrape pool re<caret>load failed\")</selection>",
   "selection": "return errors.New(\"scrape pool reload failed\")"
  }
 ]
}
```

### S03 Ctrl+W inside a struct tag

```diff
--- GoLand
+++ plugin
@@ -1,6 +1,6 @@
 package config
 
-type GlobalConfig struct {
-<selection>	ScrapeInterval string `yaml:"scrape_in<caret>terval,omitempty" json:"interval"`
-</selection>}
-
+type GlobalConfig <selection>struct {
+	ScrapeInterval string `yaml:"scrape_in<caret>terval,omitempty" json:"interval"`
+}
+</selection>
```
GoLand:
```
{
 "steps": [
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string `yaml:\"<selection>scrape_in<caret>terval</selection>,omitempty\" json:\"interval\"`",
   "selection": "scrape_interval"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string `<selection>yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"</selection>`",
   "selection": "yaml:\"scrape_interval,omitempty\" json:\"interval\""
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string <selection>`yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`</selection>",
   "selection": "`yaml:\"scrape_interval,omitempty\" json:\"interval\"`"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\t<selection>ScrapeInterval string `yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`</selection>",
   "selection": "ScrapeInterval string `yaml:\"scrape_interval,omitempty\" json:\"interval\"`"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "<selection>\tScrapeInterval string `yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`",
   "selection": "\tScrapeInterval string `yaml:\"scrape_interval,omitempty\" json:\"interval\"`\n"
  }
 ]
}
```
plugin:
```
{
 "steps": [
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string `yaml:\"<selection>scrape_in<caret>terval</selection>,omitempty\" json:\"interval\"`",
   "selection": "scrape_interval"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string <selection>`yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`</selection>",
   "selection": "`yaml:\"scrape_interval,omitempty\" json:\"interval\"`"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\t<selection>ScrapeInterval string `yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`</selection>",
   "selection": "ScrapeInterval string `yaml:\"scrape_interval,omitempty\" json:\"interval\"`"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "<selection>\tScrapeInterval string `yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`",
   "selection": "\tScrapeInterval string `yaml:\"scrape_interval,omitempty\" json:\"interval\"`\n"
  },
  {
   "key": "{ACTION:EditorSelectWord}",
   "line": "\tScrapeInterval string `yaml:\"scrape_in<caret>terval,omitempty\" json:\"interval\"`",
   "selection": "struct {\n\tScrapeInterval string `yaml:\"scrape_interval,omitempty\" json:\"interval\"`\n}\n"
  }
 ]
}
```

### K01 Ctrl+/ on one line (caret in the middle)

```diff
--- GoLand
+++ plugin
@@ -3,5 +3,5 @@
 func run() int {
 	n := 1
-	//n += load()
+	// n += load()
 	return n<caret>
 }
```

### K02 Ctrl+/ on three selected lines

```diff
--- GoLand
+++ plugin
@@ -3,7 +3,7 @@
 func run() int {
 	n := 1
-<selection>	//n += 2
-	//n *= 3
-	//n -= 4
+<selection>	// n += 2
+	// n *= 3
+	// n -= 4
 <caret></selection>	return n
 }
```

### L04 Move Statement Up past an if block

```diff
--- GoLand
+++ plugin
@@ -3,7 +3,7 @@
 func run(ok bool) int {
 	n := 0
+	n <caret>+= 2
 	if ok {
 		n++
-		n <caret>+= 2
 	}
 	return n
```

### W01 items offered for two selected statements, apply 'if'

plugin notes: surround: applied 'if'
GoLand notes: surround: applied 'if { statements }'
```diff
--- GoLand
+++ plugin
@@ -3,5 +3,5 @@
 func run(ok bool) int {
 	n := 0
-	if <caret>{
+	if <caret> {
 		n++
 		n *= 2
```
GoLand:
```
{
 "items": [
  "if { statements }",
  "if { statements } else { }",
  "for { statements }",
  "{ statements }"
 ]
}
```
plugin:
```
{
 "items": [
  "if",
  "if / else",
  "for",
  "func() { ... }()",
  "go func() { ... }()",
  "defer func() { ... }()",
  "{ ... }"
 ]
}
```

### W02 items offered for a selected expression, apply '(expr)'

plugin notes: surround: applied '(expr)'
GoLand notes: surround: applied '(expression)'
```diff
```
GoLand:
```
{
 "items": [
  "(expression)"
 ]
}
```
plugin:
```
{
 "items": [
  "(expr)"
 ]
}
```

### W03 items offered with the caret on a statement (no selection)

GoLand:
```
{
 "items": [
  "if { statements }",
  "if { statements } else { }",
  "for { statements }",
  "{ statements }"
 ]
}
```
plugin:
```
{
 "items": [
  "if",
  "if / else",
  "for",
  "func() { ... }()",
  "go func() { ... }()",
  "defer func() { ... }()",
  "{ ... }"
 ]
}
```

### G01 items with the caret inside a struct type

GoLand:
```
{
 "items": [
  "Constructor",
  "Getter and Setter",
  "Getter",
  "Setter",
  "Struct Fields from JSON",
  "Implement Methods…",
  "Empty test file",
  "Copyright"
 ]
}
```
plugin:
```
{
 "items": [
  "Implement Methods…",
  "Copyright",
  "Constructor...",
  "Method...",
  "Getters...",
  "Setters...",
  "Getters and Setters...",
  "String() Method...",
  "Equal Method...",
  "Struct Tags...",
  "Implement Interface...",
  "Tests for Package...",
  "Type from JSON..."
 ]
}
```

### G02 items at the top level of a file

GoLand:
```
{
 "items": [
  "Type from JSON",
  "Implement Methods…",
  "Empty test file",
  "Copyright"
 ]
}
```
plugin:
```
{
 "items": [
  "Copyright",
  "Tests for Package...",
  "Type from JSON..."
 ]
}
```

### G03 items inside a function body

GoLand:
```
{
 "items": [
  "Implement Methods…",
  "Empty test file",
  "Test for function",
  "Tests for file",
  "Tests for package",
  "Copyright"
 ]
}
```
plugin:
```
{
 "items": [
  "Test…",
  "Copyright",
  "Test",
  "Tests for Package...",
  "Type from JSON..."
 ]
}
```

### N10 Ctrl+Alt+B on an interface method at a call site

GoLand:
```
{
 "items": [
  "a.go:5: Commit() error",
  "impl.go:11: func (*nopAppender) Commit() error                                          { return nil }",
  "impl.go:6: func (memAppender) Commit() error                                         { return nil }"
 ]
}
```
plugin:
```
{
 "items": [
  "impl.go:11: func (*nopAppender) Commit() error                                          { return nil }",
  "impl.go:6: func (memAppender) Commit() error                                         { return nil }"
 ]
}
```

### N11 Ctrl+U from an implementing method to the interface method

GoLand:
```
{
 "error": "JavaException: java.lang.IllegalStateException: Access to Editor models (caret, selection, etc.) is allowed either from EDT, or under read action. Current thread: Thread[#161,eventLoopGroupProxy-4-1,5,main]"
}
```
plugin:
```
{
 "items": [
  "api.go:6: Commit() error"
 ],
 "value": "api.go:6: Commit() error"
}
```

### N15 Ctrl+Shift+F7 on `return` (exit points)

GoLand:
```
{
 "items": [
  "5:1-5:5 'func' SEARCH_RESULT_ATTRIBUTES",
  "7:3-7:35 'return 0, errors.New(\"negative\")' SEARCH_RESULT_ATTRIBUTES",
  "10:3-10:21 'panic(\"too large\")' SEARCH_RESULT_ATTRIBUTES",
  "12:2-12:15 'return n, nil' SEARCH_RESULT_ATTRIBUTES"
 ]
}
```
plugin:
```
{
 "items": [
  "7:3-7:35 'return 0, errors.New(\"negative\")' SEARCH_RESULT_ATTRIBUTES",
  "10:3-10:21 'panic(\"too large\")' SEARCH_RESULT_ATTRIBUTES",
  "12:2-12:15 'return n, nil' SEARCH_RESULT_ATTRIBUTES"
 ]
}
```

### N16 Ctrl+F12 file structure

GoLand:
```
{
 "items": [
  "TargetHealth: string",
  "Scraper",
  "  Scrape(ctx context.Context) error",
  "Target",
  "  Health() TargetHealth",
  "  Scrape(ctx context.Context) error",
  "  mtx: sync.RWMutex",
  "  labels: map[string]string",
  "  health: TargetHealth",
  "pool",
  "  Add(item T)",
  "  items: []T",
  "HealthUnknown: TargetHealth",
  "HealthGood: TargetHealth",
  "defaultHealth",
  "NewTarget(labels map[string]string) *Target",
  "run()"
 ]
}
```
plugin:
```
{
 "items": [
  "TargetHealth",
  "HealthUnknown TargetHealth",
  "HealthGood TargetHealth",
  "defaultHealth",
  "Scraper",
  "  Scrape(ctx context.Context) error",
  "Target",
  "  mtx sync.RWMutex",
  "  labels map[string]string",
  "  health TargetHealth",
  "  (Target) Health() TargetHealth",
  "  (Target) Scrape(ctx context.Context) error",
  "NewTarget(labels map[string]string) *Target",
  "pool[T any]",
  "  items []T",
  "  (pool) Add(item T)",
  "run()"
 ]
}
```

### H01 Ctrl+P on the second argument of a method call

GoLand:
```
{
 "items": [
  "handler: GoParameterInfoHandler, current parameter: 1",
  "start time.Time, [[duration time.Duration]], scraped int, added int, scrapeErr error"
 ]
}
```
plugin:
```
{
 "items": [
  "handler: GoParameterInfoHandler, current parameter: 1",
  "start Time, [[duration Duration]], scraped int, added int, scrapeErr error"
 ]
}
```

### H03 Ctrl+P on a generic function call

GoLand:
```
{
 "items": [
  "handler: GoParameterInfoHandler, current parameter: 1",
  "items []T, [[f func(T) U]]"
 ]
}
```
plugin:
```
{
 "items": [
  "handler: GoParameterInfoHandler, current parameter: 1",
  "[T any, U any] items []T, [[f func(T) U]]"
 ]
}
```

### H06 Ctrl+Q on a standard library function

GoLand:
```
{
 "items": [
  "<table class='sections'><tr><td valign='top' class='section'><p>Package:</td><td valign='top'><p><a href=\"psi_element://PACKAGE:strings\">strings</a></p>\n</td>\n</table>\n<div class='definition'><pre><a href=\"psi_element://KEYWORD:#func\"><span style=\"color:#cf8e6d;\">func</span></a> TrimPrefix(s <a href=\"psi_element://TYPE:builtin#builtin.string\">string</a>, prefix <a href=\"psi_element://TYPE:builtin#builtin.string\">string</a>) <a href=\"psi_element://TYPE:builtin#builtin.string\">string</a></pre></div>\n<div class='content'><p>TrimPrefix returns s without the provided leading prefix string.\nIf s doe"
 ],
 "value": "Package: strings func TrimPrefix(s string, prefix string) string TrimPrefix returns s without the provided leading prefix string. If s doesn't start with prefix, s is returned unchanged. Example Code: &#32;&#32;&#32;&#32;var&#32;s&#32;=&#32;\"¡¡¡Hello,&#32;Gophers!!!\" &#32;&#32;&#32;&#32;s&#32;=&#32;"
}
```
plugin:
```
{
 "items": [
  "<div class='definition'><pre>func TrimPrefix(s string, prefix string) string</pre></div><div class='content'><p>TrimPrefix returns s without the provided leading prefix string.\nIf s doesn&#39;t start with prefix, s is returned unchanged.</p></div><table class='sections'><tr><td valign='top' class='section'><p>Package:</td><td valign='top'><code>strings</code></td></tr><tr><td valign='top' class='section'><p>File:</td><td valign='top'>strings.go</td></tr></table>"
 ],
 "value": "func TrimPrefix(s string, prefix string) string TrimPrefix returns s without the provided leading prefix string. If s doesn't start with prefix, s is returned unchanged. Package:strings File:strings.go"
}
```

### H07 Ctrl+Q on a project function with a doc comment

GoLand:
```
{
 "items": [
  "<table class='sections'><tr><td valign='top' class='section'><p>Package:</td><td valign='top'><p><a href=\"psi_element://PACKAGE:github.com/prometheus/prometheus/typingcmp/h07\">scrape</a></p>\n</td>\n</table>\n<div class='definition'><pre><a href=\"psi_element://KEYWORD:#func\"><span style=\"color:#cf8e6d;\">func</span></a> jitter(interval <a href=\"psi_element://PACKAGE:time\">time</a>.<a href=\"psi_element://TYPE:time#time.Duration\">Duration</a>, hash <a href=\"psi_element://TYPE:builtin#builtin.uint64\">uint64</a>) <a href=\"psi_element://PACKAGE:time\">time</a>.<a href=\"psi_element://TYPE:time#time.Durat"
 ],
 "value": "Package: scrape func jitter(interval time.Duration, hash uint64) time.Duration jitter returns a deterministic offset in [0, interval) for the target with the given hash, so that targets of one job are not scraped at the same moment."
}
```
plugin:
```
{
 "items": [
  "<div class='definition'><pre>func jitter(interval Duration, hash uint64) Duration</pre></div><div class='content'><p>jitter returns a deterministic offset in [0, interval) for the target with the given hash,\nso that targets of one job are not scraped at the same moment.</p></div><table class='sections'><tr><td valign='top' class='section'><p>File:</td><td valign='top'>a.go</td></tr></table>"
 ],
 "value": "func jitter(interval Duration, hash uint64) Duration jitter returns a deterministic offset in [0, interval) for the target with the given hash, so that targets of one job are not scraped at the same moment. File:a.go"
}
```

### H08 Ctrl+Q on a struct type

GoLand:
```
{
 "items": [
  "<table class='sections'><tr><td valign='top' class='section'><p>Package:</td><td valign='top'><p><a href=\"psi_element://PACKAGE:github.com/prometheus/prometheus/typingcmp/h08\">scrape</a></p>\n</td>\n</table>\n<div class='definition'><pre><a href=\"psi_element://KEYWORD:#type\"><span style=\"color:#cf8e6d;\">type</span></a> Target <a href=\"psi_element://KEYWORD:#struct\"><span style=\"color:#cf8e6d;\">struct</span></a> {\n&nbsp;&nbsp;&nbsp;&nbsp;<a href=\"psi_element://FIELD:github.com/prometheus/prometheus/typingcmp/h08#scrape.Target.mtx\">mtx</a>    <a href=\"psi_element://PACKAGE:sync\">sync</a>.<a href=\"p"
 ],
 "value": "Package: scrape type Target struct { mtx sync.RWMutex labels map[string]string health string } Target refers to a singular HTTP or HTTPS endpoint."
}
```
plugin:
```
{
 "items": [
  "<div class='definition'><pre>type Target struct {\n\tmtx    sync.RWMutex\n\tlabels map[string]string\n\thealth string\n}</pre></div><div class='content'><p>Target refers to a singular HTTP or HTTPS endpoint.</p></div><table class='sections'><tr><td valign='top' class='section'><p>File:</td><td valign='top'>a.go</td></tr></table>"
 ],
 "value": "type Target struct { mtx sync.RWMutex labels map[string]string health string } Target refers to a singular HTTP or HTTPS endpoint. File:a.go"
}
```

### H09 Ctrl+Shift+P on an index expression

GoLand:
```
{
 "items": [
  "\"job\" -> string",
  "lset[\"job\"] -> []string"
 ]
}
```
plugin:
```
{
 "items": [
  "<no expression type>"
 ]
}
```

### H11 inlay hints in a function with literal arguments

GoLand:
```
{
 "items": [
  "5:6 above_line com.intellij.codeInsight.codeVision.ui.renderers.BlockCodeVisionInlayRenderer@564e2eb4",
  "5:25 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@78b800b6",
  "7:110 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@3babd71d",
  "9:93 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@6ea1f6c2",
  "13:13 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@256725ec",
  "14:32 inline limit:",
  "14:38 inline honorLabels:",
  "14:44 inline name:",
  "15:24 inline duration:",
  "15:27 inline scraped:",
  "15:31 inline added:",
  "15:34 inline scrapeErr:"
 ]
}
```
plugin:
```
{
 "items": [
  "14:4 inline : *scrapeLoop",
  "14:32 inline limit:",
  "14:38 inline honorLabels:",
  "14:44 inline name:",
  "15:24 inline duration:",
  "15:27 inline scraped:",
  "15:31 inline added:",
  "15:34 inline scrapeErr:",
  "16:7 inline : int",
  "16:10 inline : string"
 ]
}
```

### H12 inlay hints of an unkeyed struct literal and constants

GoLand:
```
{
 "items": [
  "3:6 above_line com.intellij.codeInsight.codeVision.ui.renderers.BlockCodeVisionInlayRenderer@5a0719f3",
  "3:21 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@73c67796",
  "9:14 inline = 1024",
  "9:24 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@4e1be734",
  "10:11 inline = 2048",
  "10:33 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@4aade423",
  "13:26 after_line_end com.intellij.codeInsight.codeVision.ui.renderers.InlineCodeVisionInlayRenderer@1e34c4d7",
  "14:19 inline T:",
  "14:25 inline V:",
  "14:32 inline T:",
  "14:38 inline V:"
 ]
}
```
plugin:
```
{
 "items": [
  "3:21 after_line_end 16 bytes",
  "9:24 inline = 1024",
  "10:33 inline = 2048",
  "14:19 inline T:",
  "14:25 inline V:",
  "14:32 inline T:",
  "14:38 inline V:"
 ]
}
```
