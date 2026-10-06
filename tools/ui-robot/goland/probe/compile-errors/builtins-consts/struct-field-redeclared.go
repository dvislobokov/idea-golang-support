package probe

type bcS struct {
	a int
	// want: a redeclared
	a string
}
