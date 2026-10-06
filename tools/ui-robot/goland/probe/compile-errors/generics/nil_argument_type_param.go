package probe

func naEql[T comparable](x, y T) bool { return x == y }

func naUse[Y interface {
	comparable
	m()
}](y Y) {
	// want: cannot use nil as Y value in argument to naEql
	naEql(y, nil)
}
