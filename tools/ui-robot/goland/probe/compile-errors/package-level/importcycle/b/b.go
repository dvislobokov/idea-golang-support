package b

// want: import cycle not allowed
import "example.com/playground/internal/probe/compile-errors/package-level/importcycle/a"

func B() int { return 1 }

var _ = a.A
