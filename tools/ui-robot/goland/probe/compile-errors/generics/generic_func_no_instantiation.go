package probe

func gfReverse[T any](s []T) []T { return s }

// want: cannot use generic function gfReverse without instantiation
var gfValue = gfReverse

var gfOk func([]int) []int = gfReverse
