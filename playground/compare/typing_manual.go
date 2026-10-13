// Package compare holds the typing / keyboard scenarios of tools/compare/typing/scenarios.txt that only an eye in the editor can judge:
// popups, inlay rendering, highlight colours. Open this file in GoLand and in the plugin's sandbox side by side, find a MARK, do what
// EXPECT says. Ids match the scenarios of docs/compare/prometheus-typing-dumps (the automated part); M* ids are manual-only.
package compare

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

// MARK:N09 / M01 — Ctrl+Alt+B popup with several implementations
// EXPECT: caret on Appender below, Ctrl+Alt+B: a popup "Choose Implementation of Appender" lists memAppender and nopAppender
// (file and package on the right), Enter on one jumps to it. NOT: a jump without a popup, an empty popup, the interface itself in the list.
type Appender interface {
	Append(ref uint64, t int64, v float64) (uint64, error)
	Commit() error
}

type memAppender struct{}

func (memAppender) Append(ref uint64, t int64, v float64) (uint64, error) { return ref, nil }
func (memAppender) Commit() error                                         { return nil }

type nopAppender struct{}

func (*nopAppender) Append(ref uint64, t int64, v float64) (uint64, error) { return 0, nil }
func (*nopAppender) Commit() error                                          { return nil }

// MARK:N11 / M02 — gutter icons and Ctrl+U
// EXPECT: an "implements" gutter icon left of memAppender.Commit and an "is implemented" icon left of Appender.Commit; Ctrl+U on
// memAppender.Commit jumps to Appender.Commit (no popup: one target). NOT: a popup with one item, an icon on unrelated lines.

// MARK:N14 / M03 — Ctrl+Shift+F7 colours of reads and writes
// EXPECT: caret on total (the := line), Ctrl+Shift+F7: writes (:=, +=, =) in the write-usage colour, reads (total < 0, return total) in
// the read-usage colour; the status bar says "5 usages found" or similar; Esc clears. NOT: one colour for all, the parameter samples marked.
func sum(samples []float64) float64 {
	total := 0.0
	for _, s := range samples {
		total += s
	}
	if total < 0 {
		total = 0
	}
	return total
}

// MARK:M04 — identifier highlighting under the caret (no keys)
// EXPECT: put the caret on mtx in Labels: both uses of mtx are softly highlighted after a moment, the field declaration too.
// NOT: highlighting of other identifiers named like a part of mtx; highlighting that stays after the caret leaves.
type Target struct {
	mtx    sync.RWMutex
	labels map[string]string
	health string
}

// Labels returns a copy of the target's labels.
func (t *Target) Labels() map[string]string {
	t.mtx.RLock()
	defer t.mtx.RUnlock()
	out := make(map[string]string, len(t.labels))
	for k, v := range t.labels {
		out[k] = v
	}
	return out
}

// MARK:H11 / M05 — inlay hints rendering
// EXPECT: grey inline hints before literal arguments: newLoop(interval: 15*time.Second?, limit: 1000, honorLabels: true, name: "node")
// — in GoLand only before literals; a type hint after sl (": *scrapeLoop") if the IDE shows variable type hints. Hints do not shift
// the caret when typing next to them; Alt+Enter on a hint offers "Do not show hints for ...". NOT: hints before non-literal arguments
// (now := time.Now()), hints that overlap text, hints that stay after the argument is deleted.
type scrapeLoop struct{}

func (sl *scrapeLoop) report(start time.Time, duration time.Duration, scraped, added int, scrapeErr error) {}

func newLoop(interval time.Duration, limit int, honorLabels bool, name string) *scrapeLoop {
	return &scrapeLoop{}
}

func runLoop() {
	sl := newLoop(15*time.Second, 1000, true, "node")
	now := time.Now()
	sl.report(now, 0, 10, 5, nil)
}

// MARK:H12 / M06 — hints of an unkeyed struct literal, constant values, struct size
// EXPECT: field-name hints T: / V: inside []Sample{{1000, 0.5}}; maybe "= 1024" after defaultLimit (plugin-only feature, GoLand has none);
// a struct-size hint at the end of the Sample line in the plugin. Compare what each IDE shows. NOT: hints in keyed literals.
type Sample struct {
	T int64
	V float64
}

const (
	defaultLimit = 1 << 10
	maxLabels    = defaultLimit * 2
)

func samples() []Sample { return []Sample{{1000, 0.5}, {2000, 1.5}, {T: 3000, V: 2.5}} }

// MARK:H01 / M07 — Ctrl+P popup: highlighted parameter moves while typing
// EXPECT: put the caret after "sl.report(now, " below and press Ctrl+P: popup "start Time, duration Duration, scraped int, added int,
// scrapeErr error" with duration in bold; type "0, " — the bold moves to scraped; the popup stays open while typing arguments and closes
// after ")". NOT: a popup that closes on the first comma, the wrong bold parameter, the popup at the wrong place.
func paramInfo(sl *scrapeLoop, now time.Time) {
	sl.report(now, 0, 10, 5, nil)
}

// MARK:H02 / M08 — Ctrl+P on a variadic call
// EXPECT: caret on err in Errorf below, Ctrl+P: "format string, a ...any" with a ...any in bold for the 2nd and every next argument.
func wrap(name string, err error) error {
	return fmt.Errorf("group %q: %w", name, err)
}

// MARK:H03 / M09 — Ctrl+P on a generic call
// EXPECT: caret after "Map(names, " below, Ctrl+P: the type parameters and the parameters, f func(T) U in bold (GoLand may show the
// instantiated types string / int). Compare the text.
func Map[T, U any](items []T, f func(T) U) []U {
	out := make([]U, 0, len(items))
	for _, it := range items {
		out = append(out, f(it))
	}
	return out
}

func lengths(names []string) []int { return Map(names, func(s string) int { return len(s) }) }

// MARK:H06 / M10 — Ctrl+Q popup
// EXPECT: caret on TrimPrefix, Ctrl+Q: a popup with the signature, the doc comment of strings.TrimPrefix, links (package, source);
// a second Ctrl+Q opens the documentation tool window. NOT: an empty popup, raw HTML, a doc of another symbol.
func trim(name string) string { return strings.TrimPrefix(name, "__") }

// MARK:H09 / M11 — Ctrl+Shift+P popup with several expressions
// EXPECT: caret right after "job" in the index below, Ctrl+Shift+P: a chooser of "job" / lset["job"]; picking lset["job"] shows []string.
// NOT: nothing at all (no expression type provider registered), a type for the wrong expression.
func value(lset map[string][]string) string {
	vs := lset["job"]
	return vs[0]
}

// MARK:N16 / M12 — Ctrl+F12 popup
// EXPECT: the file structure popup: Appender (Append, Commit), memAppender, nopAppender, Target with fields and Labels, functions;
// typing "lab" narrows to Labels/labels; the toolbar has the same filters in both IDEs (Show Fields, Inherited, ...). Compare order and icons.
// NOT: methods outside their receiver type, missing fields.

// MARK:W01 / M13 — Ctrl+Alt+T popup
// EXPECT: select the two statements inside the if below (n++ and n *= 2), Ctrl+Alt+T: a numbered list of surround templates; compare the
// list and the numbering with the other IDE; choosing "for" wraps and puts the caret into the header. NOT: an empty popup, items that
// break the code.
func surround(ok bool) int {
	n := 0
	if ok {
		n++
		n *= 2
	}
	return n
}

// MARK:G01 / M14 — Alt+Insert popup inside a struct
// EXPECT: caret inside the Target struct above, Alt+Insert: the Generate popup lists Go items (Constructor, Getter/Setter, Implement
// Methods / Interface, Struct tags...). Compare the list with docs/compare/prometheus-typing-dumps (G01) and the order. NOT: Java/Kotlin items.

// MARK:M15 — completion popup while typing (auto-popup)
// EXPECT: on the empty line in autoPopup type "ctx." — a completion popup with Deadline, Done, Err, Value appears by itself; type "D" —
// it narrows; Enter inserts "Done()" with the caret after it. NOT: no popup until Ctrl+Space, a popup on letters of a keyword in a string.
func autoPopup(ctx context.Context) error {

	return errors.New("x")
}

// MARK:M16 — Enter with the completion popup open
// EXPECT: on the empty line in autoPopup type "ctx.Er" and Enter: "ctx.Err()" inserted, no new line. Then type "\n" (Enter) again: a new
// line with the same indentation. NOT: Enter that both inserts and breaks the line.
