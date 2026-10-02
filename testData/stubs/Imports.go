//go:build linux && (amd64 || arm64)
// +build linux
// +build amd64 arm64

// Package imports covers import specs: plain, aliased, dot, blank, cgo and grouped forms.
package imports

/*
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	str "strings"
	. "math"
	_ "embed"
	"golang.org/x/tools/go/packages"
)

import `os`

func Use() {
	fmt.Println(str.ToUpper("x"), Pi, packages.NeedName, os.Args)
}
