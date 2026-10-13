package rules

// Completion probes of docs/compare part 2 (prometheus-completion-*.md). Copied into rules/ of a prometheus clone for a run and deleted
// after it. Every `// @NAME` line is an anchor: a scenario inserts its text on the line after it. The file builds as it is.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/prometheus/prometheus/model/labels"
	"github.com/prometheus/prometheus/promql/parser"
)

// probeHolder is a struct for completion probes.
type probeHolder struct {
	name     string
	interval time.Duration
	labels   labels.Labels
	logger   *slog.Logger
	// @FIELD
}

func newProbeHolder(name string) *probeHolder { return &probeHolder{name: name} }

func (h *probeHolder) Name() string { return h.name }

func (h *probeHolder) Eval(ctx context.Context, ts time.Time, expr parser.Expr) (int, error) {
	if ctx.Err() != nil {
		return 0, ctx.Err()
	}
	// @BODY
	_ = ts
	return 0, nil
}

func probeUse(m *Manager, g *Group, rules []Rule) error {
	var total int
	var err error
	ctx := context.Background()
	h := newProbeHolder("x")
	// @USE
	_, _, _, _ = total, ctx, h, g
	return err
}

func probeSwitch(s RuleHealth, r Rule) string {
	switch s {
	// @CASE
	}
	switch r.(type) {
	// @TYPECASE
	}
	return ""
}

// @TOP

func probeStrings() {
	// @STR
	_ = errors.New("x")
	_ = fmt.Sprint(strings.ToUpper("a"))
}
