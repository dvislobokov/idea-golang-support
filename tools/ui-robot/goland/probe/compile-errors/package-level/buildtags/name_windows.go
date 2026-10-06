//go:build windows

// No error expected, also when this file is opened on linux: name_linux.go declares the same names for another GOOS.
package buildtags

const Name = "windows"

func open() int { return 2 }
