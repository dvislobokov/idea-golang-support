package probe

const (
	bcC = len([1 - iota]int{})
	bcD
	// Not reported: array types inside a repeated spec are evaluated once, with the iota of the spec
	// that spells them out.
	// want: invalid array length 1 - iota (untyped int constant -1)
	bcEE
)
