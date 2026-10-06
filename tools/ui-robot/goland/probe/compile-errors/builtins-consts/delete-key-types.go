package probe

type bcM3 interface{ map[string]int | map[rune]int }

func bcDelete[T bcM3](m T) {
	// want: invalid argument: maps of m (variable of type T constrained by bcM3) must have identical key types
	delete(m, "k")
}
