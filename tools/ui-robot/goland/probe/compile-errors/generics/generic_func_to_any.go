package probe

func gaIdentity[T any](x T) T { return x }

func gaUse() {
	// want: cannot use generic function gaIdentity without instantiation
	var x any = gaIdentity
	_ = x
}
