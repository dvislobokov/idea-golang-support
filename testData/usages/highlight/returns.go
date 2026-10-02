package h

func f(x int) (int, error) {
	if x > 0 {
		return x, nil
	}
	g := func() int { return 1 }
	if x < -10 {
		panic("neg")
	}
	_ = g
	<caret>return 0, nil
}
