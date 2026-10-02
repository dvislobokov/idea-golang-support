package typeswitch

type /*def*/ S struct{ /*def*/ N int }

func (s S) /*def*/ Get() int { return s.N }

func f(v any) {
	switch /*def*/ x := v.(type) {
	case S:
		_ = /*ref*/ x./*ref*/ N
		_ = x./*ref*/ Get()
	case *S:
		_ = /*ref*/ x./*ref*/ N
	case int, string:
		_ = /*ref*/ x
	default:
		_ = /*ref*/ x
	}
	switch v.(type) {
	case S:
	}
}
