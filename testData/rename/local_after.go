package r

func f(n int) int {
	sum := 0
	for i := 0; i < n; i++ {
		sum += i
	}
	g := func() int { return sum }
	return sum + g()
}

func other() int {
	total := 1
	return total
}
