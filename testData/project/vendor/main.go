package main

import (
	"example.com/dep"
	"example.com/dep/sub"
)

func main() { _, _ = dep.A, sub.B }
