package probe

func cfUse(f float32) {
	const c float32 = 1
	_ = complex64(c)
	// want: cannot convert f (variable of type float32) to type complex64
	_ = complex64(f)
}
