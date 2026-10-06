package probe

func bcCopyMixed[T ~[]int | ~[]string](x T, y []int) {
	// want: invalid copy: mismatched slice element types int and string in x (variable of type T constrained by ~[]int | ~[]string)
	copy(x, y)
}
