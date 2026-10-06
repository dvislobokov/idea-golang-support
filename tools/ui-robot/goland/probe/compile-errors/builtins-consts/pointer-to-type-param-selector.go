package probe

func bcPtr[T interface{ m() }](x *T) {
	// want: x.m undefined (type *T is pointer to type parameter, not type parameter)
	x.m()
}
