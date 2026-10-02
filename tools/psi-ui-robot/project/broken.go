package main

import (
	"fmt"
	"os"
)

func brokenUnresolved() {
	fmt.Println(undefinedName)
}

func brokenUnused() {
	unusedVar := 42
}

func brokenMismatch() int {
	var s string = 1
	fmt.Println(s)
	return 0
}

func brokenMissingReturn(x int) int {
	if x > 0 {
		return 1
	}
}
