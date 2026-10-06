package probe

func bcCopyString[T ~string](x []int, y T) {
	// want: invalid copy: arguments x (variable of type []int) and y (variable of type T constrained by ~string) have different element types int and byte
	copy(x, y)
}
