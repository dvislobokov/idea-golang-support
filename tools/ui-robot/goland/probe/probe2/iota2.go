package probe2

const (
	I0 = iota
	I1 = 2 * I0
)

const (
	J0, J1 = iota, iota
)

const (
	K0 = iota
	K1 = iota + 10
	K2
)

const (
	L0 = -1
	L1 = iota
	L2 = iota
)

const (
	M0 = iota
	_
	M2
)

const (
	N0 = iota
	N1 = N0 + 1
	N2 = N0 + 2
)

const (
	O0 = iota
	O1 = 1
	O2 = 2
)

const (
	P0 = 1 + iota
	P1
	P2 = 3
)

func body() {
	x := 1 //no space trailing
	//no space standalone
	_ = x
}

func TypeParamsLong[item any, keyType comparable](x item, k keyType) {}
