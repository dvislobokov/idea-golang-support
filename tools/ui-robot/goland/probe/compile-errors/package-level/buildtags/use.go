//go:build linux || windows

package buildtags

// No error expected: Name and open come from the file of the current GOOS.
var Size = open() + len(Name)
