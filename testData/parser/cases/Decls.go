// Exercises declarations: grouped const with iota, grouped var/type/import, alias
// `type A = B`, generic alias, dot/blank/named imports, `import "C"` with a cgo preamble.
// Does not type-check as plain Go (cgo import, dot import clashes); parse-only.
package cases

/*
#include <stdio.h>
static void hello(void) { puts("hi"); }
*/
import "C"

import (
	_ "embed"
	. "math"
	"os"
	str "strings"
)

import "fmt"

const (
	A = iota
	B
	C0
	_
	D    = 1 << (10 * iota)
	E, F = iota, iota * 2
)

const single, other = 1, "x"

var (
	x, y int
	z    = 3
	w    string
)

type (
	Alias      = []int
	Named      []int
	Gen[T any] struct{ v T }
	S[T any]   = []T
	Fn         func(int) string
)

type A2 = Named
type S2[T any] = map[string]T

func use() { _, _, _, _ = str.ToUpper, os.Exit, fmt.Println, Pi }
