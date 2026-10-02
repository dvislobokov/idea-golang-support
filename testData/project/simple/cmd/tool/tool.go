package main

import (
	_ "example.com/simple/pkg/lib/internal/secret" // denied: outside example.com/simple/pkg/lib
	_ "internal/cpu"                               // denied: std internal
	"C"
	_ "./relative"
)

func main() {}
