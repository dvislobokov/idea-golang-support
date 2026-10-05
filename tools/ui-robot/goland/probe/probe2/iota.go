package probe2

//no leading space
const Single = iota

const (
	E0 = 1
	E1 = iota
)

const (
	F0 = iota
	F1 = iota + 1
	F2
)

const (
	G0 int = iota
	G1 int = iota
)

const (
	H0 = iota
	H1 = 7
	H2
)

var V = iota

func TypeParams[t any, Key comparable](x t, k Key) {}

type lower[k any] struct{ v k }

/*block comment*/
func BlockDoc() {}
