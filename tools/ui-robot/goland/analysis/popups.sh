#!/usr/bin/env bash
# Live popups of GoLand: Alt+Enter, Generate, Refactor This, navigation. Usage (GOLAND_PID exported):
#   bash tools/ui-robot/goland/analysis/popups.sh > docs/goland-analysis/dumps/alt-enter-generate-refactor-navigate.txt
cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/goland/analysis/gj.sh
B=internal/probeerr/broken.go
F=internal/probe/analysis.go
T=internal/probe/analysis_test.go
pop $B "unused := 42" 1 ShowIntentionActions 20-alt-enter-unused-var.png
pop $B "os.Remove(\"tmp\")" 4 ShowIntentionActions 21-alt-enter-unhandled-error.png
pop $B "\"%d items" 2 ShowIntentionActions ""
pop $B "x = x" 0 ShowIntentionActions ""
pop $B "defer f.Close()" 2 ShowIntentionActions ""
pop $B "missing(items)" 2 ShowIntentionActions 22-alt-enter-unresolved-call.png
pop $B "json:name" 3 ShowIntentionActions ""
pop $B "\"strings\"" 2 ShowIntentionActions ""
pop $B "if get() == nil" 1 ShowIntentionActions ""
pop $B "return p" 7 ShowIntentionActions ""
pop $F "type Circle struct" 6 ShowIntentionActions 23-alt-enter-struct.png
pop $F "type Shape interface" 6 ShowIntentionActions ""
pop $F "func (c Circle) Area" 16 ShowIntentionActions ""
pop $F "for _, s := range shapes" 1 ShowIntentionActions ""
pop $F "Circle{Radius: 1}" 3 ShowIntentionActions 24-alt-enter-struct-literal.png
pop $F "json:\"radius" 2 ShowIntentionActions ""
pop $F "fmt.Sprintf(\"%d %s" 14 ShowIntentionActions ""
pop $F "\"regexp\"" 2 ShowIntentionActions ""
pop $F "value, err := load(value" 1 ShowIntentionActions ""
pop $F "if value != \"\"" 1 ShowIntentionActions ""
pop $F "type Holder struct" 6 Generate 25-generate-struct.png
pop $F "func Factorial" 6 Generate ""
pop $T "func TestSum" 6 Generate 26-generate-test-file.png
pop $F "func load(" 6 Refactorings.QuickListPopupAction 27-refactor-this.png
pop $F "type Shape interface" 6 GotoImplementation 28-implementations.png
pop $F "func (c Circle) Area" 16 GotoSuperMethod ""
pop $F "func (c Circle) Area" 16 GotoRelated ""
pop $F "type Circle struct" 6 FileStructurePopup 29-file-structure.png
