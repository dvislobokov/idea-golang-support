#!/usr/bin/env bash
# Second completion pass: the probes whose text was typed open in completions.sh (`Holder{`, `select {`, `"%`) now get closed text with the
# caret at "@@", as the typed handlers would leave it. Usage: bash tools/ui-robot/goland/analysis/completions2.sh >> docs/goland-analysis/dumps/completion.txt
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
p() { echo; echo "=================== $1"; shift; activate; comp "$@"; }
B="// PROBE:body"
T="// PROBE:top"
p "C1 struct literal fields (closed braces)" "$B" "	h := Holder{@@}" "" BASIC "" 12
p "C1b struct literal: remaining fields" "$B" "	h := Holder{Name: \"x\", @@}" "" BASIC "" 12
SHOW=3 p "C1c struct literal: pick first field" "$B" "	h := Holder{@@}" "" BASIC "#1" 12
p "C1d struct literal smart" "$B" "	h := Holder{Name: @@}" "" SMART "" 12
p "C2 select case (closed)" "$B" "	select {
	case @@
	}" "" BASIC "" 12
p "C3 type switch case (closed)" "$B" "	switch v := err.(type) {
	case @@
	}" "" BASIC "" 12
p "C3b switch on Level: case (closed)" "$B" "	switch lvl {
	case @@
	}" "" BASIC "" 12
p "C4 Printf verbs (closed string)" "$B" "	fmt.Printf(\"%@@\", total)" "" BASIC "" 20
p "C4b Printf verbs AUTO after typing %" "$B" "	fmt.Printf(\"@@\", total)" "%" AUTO "" 20
p "C5 time layout (closed string)" "$B" "	_ = time.Now().Format(\"@@\")" "" BASIC "" 15
p "C6 make( (closed)" "$B" "	m := make(@@)" "" BASIC "" 12
p "C6b make smart in typed var" "$B" "	var m map[string]int = make(@@)" "" SMART "" 12
p "C7 generic function call Sum[ (closed)" "$B" "	_ = Sum[@@]" "" BASIC "" 12
p "C7b generic type Set[ (closed)" "$B" "	var st Set[@@]" "" BASIC "" 12
p "C8 struct tag key (closed backticks)" "// PROBE:field" "	Age int \`@@\`" "" BASIC "" 10
p "C8b struct tag json name (closed)" "// PROBE:field" "	FullName string \`json:\"@@\"\`" "" BASIC "" 10
p "C9 method on a type: names (body closed)" "$T" "func (h *Holder) @@() {}" "" BASIC "" 12
p "C9b Stringer method (closed)" "$T" "func (h Holder) Str@@() {}" "" BASIC "" 10
p "C10 generic constraint (closed)" "$T" "func G[T @@]() {}" "" BASIC "" 12
p "C11 variable name: var @@ Circle" "$B" "	var @@ Circle" "" BASIC "" 10
p "C11b range names: for @@ := range shapes" "$B" "	for @@ := range shapes {}" "" BASIC "" 10
p "C12 return zero values, BASIC on empty line before return" "// PROBE:ret" "	return @@" "" BASIC "" 12
p "C13 fallthrough in case (closed)" "$B" "	switch total {
	case 1:
		fallth@@
	}" "" BASIC "" 10
p "C14 go.mod: require path (closed)" "go 1.24" "require github.com/@@" "" BASIC "" 10 go.mod
p "C15 if err != nil block: smart after if" "$B" "	if @@ {}" "" SMART "" 12
