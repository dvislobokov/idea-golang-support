package p

const (
	KindInvalid Kind = iota // invalid
	KindBool                // bool
	KindInt
	KindString // string

	MaxKind = KindString
)

type Kind int

const (
	a      = 1
	bcd    = 22 // two
	efghij = 333
)

const single = 42
