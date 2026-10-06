package probe

type anChan chan int

func anUse[T ~chan int](c anChan) {
	// want: cannot use c (variable of chan type anChan) as T value in variable declaration
	var x T = c
	_ = x
}
