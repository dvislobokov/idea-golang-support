//go:build linux

// No error expected: name_windows.go declares the same names for another GOOS.
package buildtags

const Name = "linux"

func open() int { return 1 }
