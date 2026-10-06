package probe

type ctNumber interface{ ~int | ~float64 }

// want: cannot use type ctNumber outside a type constraint: interface contains type constraints
func ctSum(x ctNumber) {}
