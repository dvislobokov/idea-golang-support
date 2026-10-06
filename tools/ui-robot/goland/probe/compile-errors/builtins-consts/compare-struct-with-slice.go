package probe

type bcWithSlice struct{ s []int }

func bcCmpS(a, b bcWithSlice) bool {
	// want: invalid operation: a == b (struct containing []int cannot be compared)
	return a == b
}
