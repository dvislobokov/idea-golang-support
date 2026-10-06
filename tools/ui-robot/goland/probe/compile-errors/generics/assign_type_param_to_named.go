package probe

type atChan chan int

func atUse[T ~chan int](x T) {
	var c atChan
	// want: cannot use x (variable of type T constrained by ~chan int) as atChan value in assignment
	c = x
	_ = c
}
