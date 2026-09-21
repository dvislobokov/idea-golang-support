package store

import "os"

// Leak opens a file and forgets about it: something for golangci-lint to find (errcheck).
func Leak() {
	os.Open("x")
}
