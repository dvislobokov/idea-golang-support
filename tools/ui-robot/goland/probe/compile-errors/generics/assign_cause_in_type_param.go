package probe

func acUse[T ~chan int | ~chan byte](c chan int) {
	// want: cannot use c (variable of type chan int) as T value in variable declaration: cannot assign chan int to chan byte (in T)
	var x T = c
	_ = x
}
