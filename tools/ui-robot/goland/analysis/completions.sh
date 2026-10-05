#!/usr/bin/env bash
# Completion probes against GoLand (docs/goland-analysis). Usage (GOLAND_PID exported):
#   bash tools/ui-robot/goland/analysis/completions.sh > docs/goland-analysis/dumps/completion.txt
# comp MARK TYPE TYPED KIND [PICK] [LIMIT] [FILE]; anchors are the "// PROBE:x" lines of the probe files (the next line is empty).
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
# FROM=<probe name prefix>: skip the probes before it (rerun the tail after a broken probe)
skip() { if [ -n "$FROM" ]; then case "$1" in "$FROM"*) FROM="";; *) return 0;; esac; fi; return 1; }
p() { skip "$1" && return; echo; echo "=================== $1"; shift; activate; comp "$@"; }
# pi: pick, print the head of the document (the import block) before the probe restores it
pi() { skip "$1" && return; echo; echo "=================== $1"; shift; activate; HEAD=14 comp "$@"; }
B="// PROBE:body"
T="// PROBE:top"
TS=internal/probe/analysis_test.go
M=go.mod

p "1 fields and methods after '.' (value, embedded Base)" "$B" "	c" "." AUTO "" 25
p "2 pointer receiver methods after '.'" "$B" "	sq" "." AUTO "" 15
p "3 interface value after '.'" "$B" "	shapes[0]" "." AUTO "" 10
p "4 embedded struct after '.'" "$B" "	c.Base" "." AUTO "" 10
p "5 error value after '.'" "$B" "	err" "." AUTO "" 10
p "6 empty statement, Ctrl+Space" "$B" "	" "" BASIC "" 30
p "7 empty statement, smart" "$B" "	" "" SMART "" 15
p "8 auto-popup on letters" "$B" "	" "cl" AUTO "" 15
p "9 imported package members" "$B" "	strings" "." AUTO "" 15
p "10 package not imported: json." "$B" "	json" "." AUTO "" 15
pi "10b package not imported: pick json.Marshal (import added?)" "$B" "	json.Mar" "" BASIC "#1" 10
p "11 bare name of a symbol from another package" "$B" "	Marsh" "" BASIC "" 15
pi "11b bare name: pick (import added?)" "$B" "	NewReplac" "" BASIC "#1" 10
p "11c bare name of imported package symbol" "$B" "	ToUpp" "" BASIC "" 10
p "12 smart argument (int)" "$B" "	Factorial(" "" SMART "" 12
p "12b smart argument (any)" "$B" "	classify(" "" SMART "" 12
p "13 smart assignment to interface var" "$B" "	var s Shape = " "" SMART "" 12
p "13b smart: var _ Shape = &" "$B" "	var _ Shape = &" "" SMART "" 12
p "14 smart assignment int" "$B" "	total = " "" SMART "" 12
p "15 smart: error value" "$B" "	err = " "" SMART "" 12
p "16 smart: Level comparison" "$B" "	_ = lvl == " "" SMART "" 12
p "16b switch on Level: case" "$B" "	switch lvl {
	case " "" BASIC "" 12
p "17 errors.Is second argument" "$B" "	_ = errors.Is(err, " "" SMART "" 10
p "18 struct literal: fields" "$B" "	h := Holder{" "" BASIC "" 12
p "18b struct literal: remaining fields" "$B" "	h := Holder{Name: \"x\", " "" BASIC "" 12
SHOW=3 p "18c struct literal: pick field" "$B" "	h := Holder{" "" BASIC "Count" 12
SHOW=3 p "19 type name pick: Circle" "$B" "	x := Cir" "" BASIC "#1" 10
p "19b &Squ" "$B" "	x := &Squ" "" BASIC "" 10
SHOW=3 p "20 method value (no parens?)" "$B" "	f := c.Ar" "" BASIC "#1" 10
SHOW=3 p "21 builtin len pick" "$B" "	n := le" "" BASIC "#1" 10
SHOW=3 p "21b append pick" "$B" "	names = app" "" BASIC "#1" 10
p "21c make(" "$B" "	m := make(" "" BASIC "" 12
p "22 generic function call" "$B" "	Sum[" "" BASIC "" 10
p "22b generic type instantiation" "$B" "	var st Set[" "" BASIC "" 10
p "23 keywords: go" "$B" "	go" "" BASIC "" 10
p "23b keywords: def" "$B" "	def" "" BASIC "" 10
p "23c keywords: sel" "$B" "	sel" "" BASIC "" 10
p "23d keywords: range" "$B" "	for _, n := ra" "" BASIC "" 10
p "23e keywords: fallthrough in case" "$B" "	switch total {
	case 1:
		fallth" "" BASIC "" 10
p "24 channel send (smart)" "$B" "	ch <- " "" SMART "" 10
p "24b channel receive" "$B" "	v := <-" "" BASIC "" 10
p "25 select case" "$B" "	select {
	case " "" BASIC "" 10
p "26 type switch case" "$B" "	switch v := err.(type) {
	case " "" BASIC "" 12
p "27 variable name after var" "$B" "	var " "" BASIC "" 10
p "27b variable name for type (var  Circle)" "$B" "	var c2 Circle; var " "" BASIC "" 10
p "28 Printf verbs" "$B" "	fmt.Printf(\"%" "" BASIC "" 20
p "29 time layout in Format" "$B" "	_ = time.Now().Format(\"" "" BASIC "" 15
p "30 regexp in MustCompile" "$B" "	_ = regexp.MustCompile(\`\\" "" BASIC "" 15
p "31 postfix list after expression" "$B" "	names.fo" "" BASIC "" 15
SHOW=6 p "31b postfix .forr" "$B" "	names.forr" "" BASIC "forr" 10
SHOW=6 p "31c postfix .for" "$B" "	names.for" "" BASIC "for" 10
SHOW=6 p "31d postfix .nn on error" "$B" "	err.nn" "" BASIC "nn" 10
SHOW=6 p "31e postfix .nil" "$B" "	err.nil" "" BASIC "nil" 10
SHOW=8 p "31f postfix .rr on call returning error" "$B" "	load(\"x\").rr" "" BASIC "rr" 10
SHOW=8 p "31g postfix .varCheckError" "$B" "	load(\"x\").varCheckError" "" BASIC "varCheckError" 10
SHOW=4 p "31h postfix .var" "$B" "	c.Area().var" "" BASIC "var" 10
SHOW=4 p "31i postfix .return" "$B" "	err.return" "" BASIC "return" 10
SHOW=6 p "31j postfix .if on bool" "$B" "	(total > 0).if" "" BASIC "if" 10
p "31k postfix .sort variants" "$B" "	names.sort" "" BASIC "" 10
SHOW=4 p "31l postfix .print" "$B" "	total.print" "" BASIC "print" 10
SHOW=6 p "32 live template forr" "$B" "	forr" "" BASIC "forr" 10
SHOW=6 p "32b live template fori" "$B" "	fori" "" BASIC "fori" 10
SHOW=6 p "32c live template err" "$B" "	err" "" BASIC "err" 10
SHOW=4 p "32d live template printf" "$B" "	printf" "" BASIC "printf" 10
SHOW=6 p "33 live template meth (top level)" "$T" "meth" "" BASIC "meth" 10
SHOW=6 p "33b live template main" "$T" "main" "" BASIC "main" 10
SHOW=6 p "33c live template test" "$T" "test" "" BASIC "test" 10
SHOW=6 p "33d live template bench" "$T" "bench" "" BASIC "bench" 10
SHOW=6 p "33e func keyword at top level" "$T" "fun" "" BASIC "" 10
p "34 method declaration: receiver type" "$T" "func (h *Hol" "" BASIC "" 10
p "34b method name on a type (interface methods offered?)" "$T" "func (h *Holder) " "" BASIC "" 12
p "34c Stringer method" "$T" "func (h Holder) Str" "" BASIC "" 10
p "35 generic constraint" "$T" "func G[T " "" BASIC "" 12
p "35b parameter name" "$T" "func g(" "" BASIC "" 10
p "36 return (smart) with several results" "// PROBE:ret" "	return " "" SMART "" 12
p "36b return (basic)" "// PROBE:ret" "	return " "" BASIC "" 12
p "37 struct tag: backtick" "// PROBE:field" "	Age int " "\`" AUTO "" 10
p "37b struct tag key" "// PROBE:field" "	Age int \`" "" BASIC "" 10
p "37c struct tag json name" "// PROBE:field" "	FullName string \`json:\"" "" BASIC "" 10
p "38 import path" "import (" "	\"encoding/" "" BASIC "" 15
p "38b import path, module" "import (" "	\"github.com/" "" BASIC "" 10
p "39 test file: t." "// PROBE:test" "	t" "." AUTO "" 20 $TS
p "39b test file: assert (no testify)" "// PROBE:test" "	assert" "" BASIC "" 10 $TS
p "39c test file: t.Run(" "// PROBE:test" "	t.Run(" "" BASIC "" 10 $TS
p "40 go.mod: directives" "go 1.24" "" "" BASIC "" 20 $M
p "40b go.mod: require path" "go 1.24" "require github.com/google/" "" BASIC "" 10 $M
p "40c go.mod: module version" "go 1.24" "require github.com/google/uuid " "" BASIC "" 10 $M
