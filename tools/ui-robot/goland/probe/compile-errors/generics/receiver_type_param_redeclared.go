package probe

type rdPair[K comparable, V any] struct {
	k K
	v V
}

// want: E redeclared in this block
func (p *rdPair[E, E]) dup() {}
