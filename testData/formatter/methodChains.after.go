package p

type B struct{}

func (b *B) A() *B    { return b }
func (b *B) C(int) *B { return b }

func f(b *B) {
	b.A().
		C(1).
		A()
	x := b.
		A()
	_ = x
}
