package probe

import "strings"

// Type names picked where a value goes (GoLand probe 19: `x := Cir` -> `Circle{<caret>}`).
// Each "// caret:" line names what is typed on the empty line below it (BASIC completion, pick the row) and the expected text.

type tlCircle struct{ Radius float64 }

type tlNames []string

type tlIndex map[string]int

type tlLevel int

func tlUse(c tlCircle) {}

func tlMake() tlCircle {
	// caret: `return tlCir` + pick `tlCircle` -> `return tlCircle{<caret>}`

	return tlCircle{}
}

func tlBody() {
	// caret: `x := tlCir` + pick `tlCircle` -> `x := tlCircle{<caret>}`

	// caret: `tlUse(tlCir` + pick `tlCircle` -> `tlUse(tlCircle{<caret>}`

	// caret: `p := &tlCir` + pick `tlCircle` -> `p := &tlCircle{<caret>}`

	// caret: `n := tlNam` + pick `tlNames` -> `n := tlNames{<caret>}`; `m := tlInd` -> `m := tlIndex{<caret>}`

	// caret: `l := tlLev` + pick `tlLevel` -> `l := tlLevel` (no braces: not a composite type)

	// caret: `var c tlCir` + pick `tlCircle` -> `var c tlCircle` (type position)

	// caret: `xs := []tlCir` + pick `tlCircle` -> `xs := []tlCircle` (type position)

	// caret: `m := make(tlNam` + pick `tlNames` -> `m := make(tlNames` (type argument of make)

	// caret: `b := strings.Build` + pick `Builder` -> `b := strings.Builder{<caret>}`

	_ = strings.ToUpper
}
