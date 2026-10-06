package probe

func bcCloseRecv[T chan int | <-chan int](ch T) {
	// want: invalid operation: cannot close receive-only channel ch (variable of type T constrained by chan int | <-chan int)
	close(ch)
}
