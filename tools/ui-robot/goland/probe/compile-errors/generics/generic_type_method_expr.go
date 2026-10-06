package probe

type gmT[P any] int

func (gmT[P]) m() {}

func gmUse() {
	// want: cannot use generic type gmT without instantiation
	_ = gmT.m
}
