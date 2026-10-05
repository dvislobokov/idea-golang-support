// Package probe2 mirrors tools/ui-robot/goland/probe/probe2 (GoLand 2026.2.3, 2026-10-05).
package probe2

import (
	"os"
	"strings"
	"time"
)

// Probe2Config is a struct.
type Probe2Config struct{ name string }

// Generic is generic.
type Generic[T any] struct{ v T }

// Exported is exported; it has no doc of its own words.
func Exported() {}

func shadow(ch chan int) {
	x := 1
	if x > 0 {
		x := 2
		_ = x
	}
	_ = x
	select {
	case n := <-ch:
		_ = n
	default:
	}
	new := 2
	_ = new
	_ = strings.ToUpper("a")
}

// Probe2Config is used by Exported with [Generic] and time.Duration; see also os.PathError, iota and time.
type Doc struct{}

var _ = os.Args
var _ time.Duration
