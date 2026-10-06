package probe

func bcCopyDst[T ~[]byte](y T) {
	// want: invalid copy: argument must be a slice; have "foo" (untyped string constant)
	copy("foo", y)
}
