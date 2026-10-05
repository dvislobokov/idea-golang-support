#!/usr/bin/env bash
# Typing assist probes against GoLand. Usage (GOLAND_PID exported):
#   bash tools/ui-robot/goland/analysis/typing.sh > docs/goland-analysis/dumps/typing-assists.txt
# ty NAME MARK TYPE KEYS: TYPE is inserted at the line after MARK, KEYS typed ({ENTER} {TAB} {BS} {CSE} {ESC} are actions).
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
ty() {
  echo; echo "=================== $1"; activate
  gj $A/typing.js "s|__FILE__|$P/${FILE:-internal/probe/analysis.go}|" "s|__MARK__|$(esc "$2")|" "s|__TYPE__|$(esc "$3")|" "s|__KEYS__|$(esc "$4")|" "s|__SYNC__|1000|" "s|__PAUSE__|${PAUSE:-250}|" "s|__AFTER__|${AFTER:-1200}|" "s|__SHOW__|${SHOW:-5}|" "s|__STEPS__|${STEPS:-no}|"
}
B="// PROBE:body"
T="// PROBE:top"
STEPS=yes ty "1 '(' after function name" "$B" "	load" "("
STEPS=yes ty "2 '\"' opens a pair, overtyped at the end" "$B" "	s := " "\"ab\""
STEPS=yes ty "3 backtick pair" "$B" "	s := " "\`ab\`"
STEPS=yes ty "4 '[' and '{' pairs" "$B" "	a := " "[]int{1}"
STEPS=yes ty "5 rune quote" "$B" "	r := " "'x'"
SHOW=7 ty "6 Enter between braces after if" "$B" "	if total > 0 " "{{ENTER}"
SHOW=7 ty "7 '{' Enter after func header (top level)" "$T" "func newOne() " "{{ENTER}"
SHOW=5 ty "8 Enter in a line comment continues it?" "$B" "	// first part second" "{BS}{BS}{BS}{BS}{BS}{BS}{BS}{ENTER}"
SHOW=5 ty "9 Enter inside a string literal splits it" "$B" "	s := \"hello world\"" "{BS}{BS}{BS}{BS}{BS}{BS}{ENTER}"
SHOW=7 ty "10 if err + Tab (live template?)" "$B" "	_, err = load(\"x\")
	err" "{TAB}"
SHOW=6 ty "11 Ctrl+Shift+Enter in if" "$B" "	if total > 0" "{CSE}"
SHOW=6 ty "12 Ctrl+Shift+Enter in for" "$B" "	for i := 0; i < 3; i++" "{CSE}"
SHOW=6 ty "13 Ctrl+Shift+Enter on a call" "$B" "	load(\"x\"" "{CSE}"
SHOW=7 ty "14 Ctrl+Shift+Enter on func header (top level)" "$T" "func other(a int) error" "{CSE}"
SHOW=6 ty "15 Smart Enter in a struct literal" "$B" "	h := Holder{Name: \"x\"" "{CSE}"
SHOW=7 ty "16 Enter after 'case X:'" "$B" "	switch total {
	case 1:" "{ENTER}"
SHOW=7 ty "17 '}' reformats block" "$B" "	if total>0 {   total=1   " "}"
SHOW=5 ty "18 Backspace removes pair" "$B" "	s := " "({BS}"
SHOW=5 ty "19 ':' after a name (:= assist?)" "$B" "	nv " ":=1"
SHOW=5 ty "20 auto-popup on letters (identifier start)" "$B" "	" "lo"
SHOW=8 ty "21 '/' then Enter above a func: doc comment stub?" "$T" "" "//{ENTER}"
STEPS=yes ty "22 '.' after method name inserts ()?" "$B" "	x := c.Name" "."
SHOW=5 ty "23 Enter in import block" "import (" "	\"os\"" "{ENTER}"
SHOW=4 AFTER=4000 ty "24 save runs gofmt (Actions on Save)" "$B" "	total=total+1" "{SAVE}"
SHOW=4 AFTER=3000 ty "25 Reformat Code (Ctrl+Alt+L)" "$B" "	total=total+1" "{FMT}"
SHOW=14 FILE=internal/probeerr/broken.go AFTER=3000 ty "26 Optimize Imports removes the unused import" "// Package probeerr has" "" "{OPT}"
