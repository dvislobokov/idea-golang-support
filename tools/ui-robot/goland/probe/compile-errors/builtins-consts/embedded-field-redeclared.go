package probe

type bcE struct{ x int }

type bcEmb struct {
	bcE
	// want: bcE redeclared
	*bcE
}
