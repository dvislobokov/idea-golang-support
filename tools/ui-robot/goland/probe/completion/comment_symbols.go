package probe

// Completion in top-level comments (GoLand 2026.2.3: Ctrl+Space only, no auto-popup).
//
// caret: end of the line "// Cir" above Circle, Ctrl+Space; expect `Circle` (struct icon) first, then hump matches (`NewCircle`).
// caret: end of the line "// " above Circle (delete "Cir"), Ctrl+Space; expect `Circle` first (the documented declaration), then
//        the exported names of the package in source order (`Circle`, `Area` {method}, `NewCircle`, `Default`, ... of every file of
//        the package), then the unexported ones (`maxItems`, `helper`).
// caret: end of "// Co" inside helper's body, Ctrl+Space; expect no package names (words of the file at most).
// caret: typing a letter after "// " must not open the popup.

// Cir
type Circle struct{ R float64 }

// Area is the area.
func (c Circle) Area() float64 { return 3.14 * c.R * c.R }

// NewCircle makes a circle.
func NewCircle(r float64) Circle { return Circle{R: r} }

// Default is the unit circle.
var Default = NewCircle(1)

const maxItems = 10

func helper() int {
	// Co
	return maxItems
}
