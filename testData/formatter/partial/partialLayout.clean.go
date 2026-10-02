package p

import (
	"fmt"
	"os"
)

type T struct {
	A  int
	BB string // c
}

func clean1(a int, b int) int {
	x := a + b
	if x > 0 {
		return x
	}
	return 0
}

func clean2() {
	var m = map[string]int{"a": 1, "bbb": 2}
	_ = m
}

// clean3 prints.
func clean3(s []string) {
	for i, v := range s {
		fmt.Println(i, v)
	}
	os.Exit(0)
}
