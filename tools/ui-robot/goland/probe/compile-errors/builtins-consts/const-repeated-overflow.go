package probe

const (
	bcB0 = byte(iota + 254)
	bcB1
	// want: constant 256 overflows byte
	bcB2
)
