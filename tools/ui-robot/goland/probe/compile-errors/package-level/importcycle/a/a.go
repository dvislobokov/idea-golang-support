// Package a imports b, and b imports a.
package a

// want: import cycle not allowed
import "example.com/playground/internal/probe/compile-errors/package-level/importcycle/b"

func A() int { return b.B() }
