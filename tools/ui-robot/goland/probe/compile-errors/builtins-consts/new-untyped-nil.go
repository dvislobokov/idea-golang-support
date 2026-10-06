package probe

func bcNewNil() {
	// want: use of untyped nil in argument to new
	_ = new(nil)
}
