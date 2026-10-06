package probe

import (
	"strings"
	"testing"
)

// Row presentation (GoLand probes 1, 2, 3, 6, 9, 39, C1c): `name tail  : type`, the tail of a member ends with `→ Owner`.

type prBase struct {
	ID      int
	created string
}

func (b prBase) Describe() string { return "" }

type prCircle struct {
	prBase
	Radius float64
	Data   []byte
}

func (c prCircle) Area() float64 { return 0 }

type prSquare struct{ Side float64 }

func (s *prSquare) Area() float64 { return 0 }

type prShape interface{ Area() float64 }

func prRunes(b []byte, r rune) (byte, rune) { return 0, 0 }

func prRows(c prCircle, sq *prSquare, s prShape, t *testing.T) {
	// caret: `c.` AUTO -> `ID → prBase  : int`, `created → prBase  : string`, `Radius → prCircle  : float64`, `Data → prCircle  : []byte`,
	// `Area() → prCircle  : float64`, `Describe() → prBase  : string`, `prBase  : prBase` (embedded field: no owner)

	// caret: `sq.` AUTO -> `Side → prSquare  : float64`, `Area() → *prSquare  : float64`

	// caret: `s.` AUTO -> `Area() → interface {...}  : float64`

	// caret: `t.` AUTO -> `Errorf(format string, args ...any) → *common`, `Run(name string, f func(t *T)) → *T  : bool`

	// caret: `x := prCircle{@@}` BASIC -> `Radius → prCircle  : float64`, `ID → prBase  : int`

	// caret: `strings.` AUTO -> `ToUpper(s string)  : string`, `IndexByte(s string, c byte)  : int`, `Cut(s string, sep string)  : (before string, after string, found bool)`

	// caret: `json.` AUTO (not imported) -> `Marshal(v any) encoding/json  : ([]byte, error)`

	// caret: `prRu` BASIC -> `prRunes(b []byte, r rune)  : (byte, rune)`; `c` -> `c  : prCircle`; `le` -> `len(v Type)  : int`

	_ = strings.ToUpper
}
