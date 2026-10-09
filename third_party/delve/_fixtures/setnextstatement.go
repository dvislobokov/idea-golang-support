package main

import "fmt"

func demo() {
	n := 0
	n++
	n++
	fmt.Println(n)
}

func other() {
	fmt.Println("other")
}

func main() {
	demo()
	other()
}
