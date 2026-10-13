// Completion scenarios of docs/compare/prometheus-completion-2026-10-13.md that are judged in the editor: what the list shows, what
// is inserted and where the caret lands. Open this file in GoLand and in IDEA with the plugin side by side, find a MARK, do what
// EXPECT says. Ids match tools/compare/completion/scenarios.json (the automated part runs on prometheus, these are the same cases on
// playground code). Restore the file after each case (Ctrl+Z).
package compare

import (
	"context"
	"errors"
	"log/slog"
	"time"
)

type probeRule interface {
	Name() string
	Labels() []string
}

type probeAlerting struct{ name string }

func (r *probeAlerting) Name() string     { return r.name }
func (r *probeAlerting) Labels() []string { return nil }

type probeAlert struct{ id int }

type probeHealth string

const (
	probeHealthGood    probeHealth = "ok"
	probeHealthBad     probeHealth = "err"
	probeHealthUnknown probeHealth = "unknown"
)

type probeHolder struct {
	name     string
	interval time.Duration
	logger   *slog.Logger
}

func (h *probeHolder) Eval(ctx context.Context, ts time.Time) (int, error) {
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}
	_ = ts
	return 0, nil
}

func probeCompletion(h *probeHolder, rules []probeRule, r probeRule, s probeHealth) error {
	var err error
	ctx := context.Background()

	// MARK:C09 — two packages with one name
	// EXPECT: on the empty line below type `_ = rand.Int` and press Ctrl+Space: a list with math/rand, crypto/rand, math/rand/v2 (the
	// package path on the right of each item); Enter on the math/rand one adds import "math/rand". NOT (plugin today): `rand.Int(` is
	// inserted at once with import "crypto/rand" and no choice.

	// MARK:C10 — the same with template
	// EXPECT: `_ = template.New` + Ctrl+Space lists html/template and text/template; the chosen one is imported. NOT: html/template
	// imported silently.

	// MARK:C19 — postfix .rr on an error
	// EXPECT: below type `err.rr` and Tab (or pick `rr` from Ctrl+Space): `if err != nil {⏎ return err⏎}` with the caret after `return err`.
	// NOT (plugin today): `return err, nil`.

	// MARK:C20 — postfix .var on a call
	// EXPECT: `h.Eval(ctx, time.Now()).var` + Tab: `name, err := h.Eval(...)` (or `i, err :=`) with the caret ON the name, ready to type
	// a new one (live template). NOT: the caret after the expression with no way to rename.

	// MARK:C17 — postfix .for on a slice
	// EXPECT (GoLand): `rules.for` + Tab gives `for i := range rules {` with the caret on `i`; the plugin gives `for _, rule := range rules {`
	// with the caret in the body. Judge which you prefer; both compile.

	// MARK:C36 — a field of a struct literal
	// EXPECT: `_ = probeHolder{na` + Ctrl+Space, Enter on the first item: `probeHolder{name: <caret>}`. The plugin's first item is
	// `name: h.name` (a guessed value) — check that it is clearly marked as a guess and that `name` alone is the next item.

	// MARK:C38 — case of a type switch on an interface
	// EXPECT: inside `switch r.(type) { case *probeA| }` Ctrl+Space: probeAlerting (implements probeRule) before probeAlert (does not).
	// GoLand puts probeAlert first (shorter name); the plugin ranks by the interface — keep that.

	// MARK:C39 — case of a switch on a typed string
	// EXPECT: inside `switch s { case probeH| }` the three probeHealth constants first, in declaration order (Good, Bad, Unknown).

	// MARK:C35 — `if e` after an error assignment
	// EXPECT: `if e` + Ctrl+Space: `err` first (GoLand puts the package `errors` first). NOT: `errors` first.

	// MARK:C25 — top level
	// EXPECT: on the empty line after this function type `f` + Ctrl+Space: keywords func / for nothing else statement-like; the
	// `func` item first. NOT (plugin today): 50 items "func (ph *probeHolder) …  missing method of TBRun" before `func`.

	// MARK:C30 / C31 — inside a string and a comment
	// EXPECT: in `"he|"` Ctrl+Space shows nothing (or "No suggestions"); in a comment at most plain words from the file. NOT: code items.

	_, _, _, _, _ = err, ctx, rules, r, s
	_ = errors.New
	return nil
}
