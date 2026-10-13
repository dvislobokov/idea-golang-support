Plugin: plugin; GoLand: no result file

| id | category | scenario | GoLand | plugin | verdict |
|---|---|---|---|---|---|
| E01 | enter | Enter between the braces of an if |  | `<caret>` | — |
| E02 | enter | '{' then Enter after a range header |  | `<caret>` | — |
| E03 | enter | Enter in the middle of an expression |  | `<caret>seriesAdded` | — |
| E04 | enter | Enter inside a line comment |  | `// <caret>appended per scrape.` | — |
| E05 | enter | Enter inside an interpreted string |  | `<caret>reload failed")` | — |
| E06 | enter | Enter inside a raw string |  | `<caret>rate(http_requests_total[5m]))`` | — |
| E07 | enter | Enter after a comma in a struct literal |  | `<caret>ScrapeInterval: 15 * time.Second}` | — |
| E08 | enter | Enter after a comma in an argument list |  | `<caret>"err", err)` | — |
| E10 | enter | Enter after a case clause |  | `<caret>` | — |
| C01 | complete | Complete Statement after `if err != nil` |  | `<caret>` | — |
| C02 | complete | Complete Statement after a for header |  | `<caret>` | — |
| C03 | complete | Complete Statement on `func f(` without `)` |  | `<caret>` | — |
| C04 | complete | Complete Statement on a call without `)` |  | `<caret>` | — |
| C05 | complete | Complete Statement on `return fmt.Errorf(` without `)` |  | `<caret>` | — |
| P01 | pairs | '(' after a method name |  | `sp.reload(<caret>)` | — |
| P02 | pairs | '"' opens a pair, the closing one is overtyped |  | `name := "nod<caret>"; name := "node<caret>"; name := "node"<caret>` | — |
| P03 | pairs | backtick struct tag with quotes inside |  | `ScrapeInterval time.Duration `yaml:""scrape_interval<caret>"`; ScrapeInterval time.Duration `yaml:""scrape_i… | — |
| P04 | pairs | '[' and '{' of a slice literal |  | `ls := []string{"job<caret>"}; ls := []string{"job"<caret>}; ls := []string{"job"}<caret>` | — |
| P05 | pairs | rune quote |  | `s := '<caret>'; s := ',<caret>'; s := ','<caret>` | — |
| P06 | pairs | Backspace on an empty () |  | `sp.reload<caret>` | — |
| P07 | pairs | Backspace on an empty "" |  | `s := <caret>` | — |
| P08 | pairs | Backspace on an empty {} of a map literal |  | `m := map[string]string<caret>` | — |
| P09 | pairs | ')' overtypes the existing closing parenthesis |  | `apply(cfg)<caret>` | — |
| P10 | pairs | ':' and '=' after a name |  | `n := 1<caret>` | — |
| I01 | indent | paste of unindented lines into a function body |  | `<caret>` | — |
| I02 | indent | paste of 4-space lines into a nested block |  | `<caret>` | — |
| I03 | indent | paste of a fragment in the middle of a line |  | `return interval - 500*time.Millisecond<caret>` | — |
| I04 | indent | paste of a top-level function with indentation |  | `<caret>` | — |
| I05 | indent | '}' on its own line dedents |  | `}<caret>` | — |
| I06 | indent | '}' closing a badly spaced one-line block |  | `if total>0 {   total=1   }<caret>` | — |
| F01 | format | Reformat Code on operators and braces |  | `<caret>total = total + 1` | — (plugin = gofmt) |
| F02 | format | Reformat Code aligns struct fields, tags and comments |  | `<caret>JobName        string        `yaml:"job_name"` // the job` | — (plugin = gofmt) |
| F03 | format | Reformat Code aligns a const block |  | `<caret>HealthUnknown TargetHealth = "unknown"` | — (plugin = gofmt) |
| F04 | format | Reformat Code on a map literal and spaces for indentation |  | `<caret>return m` | — (plugin = gofmt) |
| F06 | format | save of a badly formatted file (actions on save) |  | `total = total + 1*2<caret>` | — (plugin ≠ gofmt) |
| S01 | select | Ctrl+W from an identifier inside a call in an if condition |  | `<selection>→if c.forEach<caret>Stale(func(s string) bool { return len(s) > 0 }) { [→if c.forEachStale(func(s… | — |
| S02 | select | Ctrl+W inside a string literal |  | `return errors.New<selection>("scrape pool re<caret>load failed")</selection> [("scrape pool reload failed")]… | — |
| S03 | select | Ctrl+W inside a struct tag |  | `<selection>ScrapeInterval string `yaml:"scrape_in<caret>terval,omitempty" json:"interval"`</selection> [Scra… | — |
| S04 | select | Ctrl+W four times, then Ctrl+Shift+W twice |  | `return float64(added) / <selection>float64(to<caret>tal+1)</selection> [float64(total+1)]; return float64(ad… | — |
| K01 | comment | Ctrl+/ on one line (caret in the middle) |  | `return n<caret>` | — |
| K02 | comment | Ctrl+/ on three selected lines |  | `<caret></selection>→return n` | — |
| K03 | comment | Ctrl+/ uncomments selected lines |  | `n *= 3<caret></selection>` | — |
| K04 | comment | Ctrl+Shift+/ on a selected expression |  | `return total + <selection>/*step*2<caret>*/</selection>` | — |
| L01 | lines | Duplicate Line |  | `ls = append(ls, "j<caret>ob")` | — |
| L03 | lines | Move Line Up into the block above |  | `n <caret>+= 2` | — |
| L04 | lines | Move Statement Up past an if block |  | `n <caret>+= 2` | — |
| L05 | lines | Move Statement Down of a whole function |  | `func fi<caret>rst() int {` | — |
| O01 | imports | Optimize Imports removes unused imports |  | `<caret>return fmt.Errorf("timeout %v", time.Second)` | — |
| O02 | imports | Optimize Imports with unsorted, aliased and blank imports |  | `<caret>stdlog.Println(time.Now())` | — |
| O03 | imports | Optimize Imports leaves one import of a group |  | `<caret>fmt.Println()` | — |
| W01 | surround | items offered for two selected statements, apply 'if' |  | items: if, if / else, for, func() { ... }(), go func() { ... }(), defer func() { ... }(), { ... } → `if <care… | — |
| W02 | surround | items offered for a selected expression, apply '(expr)' |  | items: (expr) → `return (added + total)<caret>` | — |
| W03 | surround | items offered with the caret on a statement (no selection) |  | if; if / else; for; func() { ... }(); go func() { ... }(); defer func() { ... }(); { ... } | — |
| G01 | generate | items with the caret inside a struct type |  | Implement Methods…; Copyright; Constructor...; Method...; Getters...; Setters...; Getters and Setters...; Str… | — |
| G02 | generate | items at the top level of a file |  | Copyright; Tests for Package...; Type from JSON... | — |
| G03 | generate | items inside a function body |  | Test…; Copyright; Test; Tests for Package...; Type from JSON... | — |
| N01 | nav | Ctrl+B on a local variable |  | a.go:4: seriesAdded := added * 2 | — |
| N02 | nav | Ctrl+B on a method declared in another file of the package |  | pool.go:6: func (sp *scrapePool) reload(targets []string) { | — |
| N03 | nav | Ctrl+B on a standard library function |  | $GOROOT/src/strings/strings.go:501: func HasPrefix(s, prefix string) bool { | — |
| N04 | nav | Ctrl+B on an import path |  | dir:$GOROOT/src/net/http | — |
| N06 | nav | Ctrl+B on an embedded field in a selector |  | a.go:6: sync.RWMutex | — |
| N07 | nav | Ctrl+B on a method promoted from an embedded field |  | $GOROOT/src/sync/rwmutex.go:114: func (rw *RWMutex) RUnlock() { | — |
| N09 | nav | Ctrl+Alt+B on an interface type |  | impl.go:3: type memAppender struct{}; impl.go:8: type nopAppender struct{} | — |
| N10 | nav | Ctrl+Alt+B on an interface method at a call site |  | impl.go:11: func (*nopAppender) Commit() error                                          { return nil }; impl.… | — |
| N11 | nav | Ctrl+U from an implementing method to the interface method |  | api.go:6: Commit() error | — |
| N12 | nav | Alt+F7 on a method used in two files |  | count: 3; a.go:10: sp.reload(nil); loop.go:4: sp.reload(nil); loop.go:6: sp.reload([]string{"a"}) | — |
| N13 | nav | Alt+F7 on a struct field |  | count: 3; a.go:11: t.health = h; a.go:12: _ = Target{health: h}; a.go:8: func (t *Target) up() bool { return … | — |
| N14 | nav | Ctrl+Shift+F7 on a local variable (reads and writes) |  | 4:2-4:7 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 6:3-6:8 'total' WRITE_SEARCH_RESULT_ATTRIBUTES; 8:5-8:10 'tot… | — |
| N15 | nav | Ctrl+Shift+F7 on `return` (exit points) |  | 7:3-7:35 'return 0, errors.New("negative")' SEARCH_RESULT_ATTRIBUTES; 10:3-10:21 'panic("too large")' SEARCH_… | — |
| N16 | nav | Ctrl+F12 file structure |  | TargetHealth; HealthUnknown TargetHealth; HealthGood TargetHealth; defaultHealth; Scraper;   Scrape(ctx conte… | — |
| H01 | hints | Ctrl+P on the second argument of a method call |  | start Time, [[duration Duration]], scraped int, added int, scrapeErr error | — |
| H02 | hints | Ctrl+P on a variadic argument of fmt.Errorf |  | format string, [[a ...any]] | — |
| H03 | hints | Ctrl+P on a generic function call |  | [T any, U any] items []T, [[f func(T) U]] | — |
| H04 | hints | Ctrl+P on a standard library call |  | s string, [[count int]] | — |
| H06 | hints | Ctrl+Q on a standard library function |  | func TrimPrefix(s string, prefix string) string TrimPrefix returns s without the provided leading prefix stri… | — |
| H07 | hints | Ctrl+Q on a project function with a doc comment |  | func jitter(interval Duration, hash uint64) Duration jitter returns a deterministic offset in [0, interval) f… | — |
| H08 | hints | Ctrl+Q on a struct type |  | type Target struct { mtx sync.RWMutex labels map[string]string health string } Target refers to a singular HT… | — |
| H09 | hints | Ctrl+Shift+P on an index expression |  | <no expression type> | — |
| H11 | hints | inlay hints in a function with literal arguments |  | 14:4 inline : *scrapeLoop; 14:32 inline limit:; 14:38 inline honorLabels:; 14:44 inline name:; 15:24 inline d… | — |
| H12 | hints | inlay hints of an unkeyed struct literal and constants |  | 3:21 after_line_end 16 bytes; 9:24 inline = 1024; 10:33 inline = 2048; 14:19 inline T:; 14:25 inline V:; 14:3… | — |

—: 80
