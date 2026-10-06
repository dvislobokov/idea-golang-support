package probe

type imT interface {
	// want: interface method must have no type parameters
	F[Z any]()
}
