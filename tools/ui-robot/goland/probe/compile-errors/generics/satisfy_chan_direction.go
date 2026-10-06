package probe

func sdF[T any, C chan T | <-chan T](ch C) {}

func sdUse(ch chan<- int) {
	// want: chan<- int does not satisfy chan int | <-chan int (chan<- int missing in chan int | <-chan int)
	sdF(ch)
}
