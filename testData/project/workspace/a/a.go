package a

import (
	"example.com/b/bpkg"
	_ "example.com/b/internal/hidden" // denied: different module tree
)

var X = bpkg.Y
