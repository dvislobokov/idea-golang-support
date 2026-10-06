package main

func main(args []string) int { // ERROR "func main must have no arguments and no return values"
	_ = args
	return 0
}
