package initcycle

// f reads x, whose initializer in a.go calls f.
func f() int { return x + 1 }
