package r

func f(n int) int {
	total<caret> := 0
	for i := 0; i < n; i++ {
		total += i
	}
	g := func() int { return total }
	return total + g()
}

func other() int {
	total := 1
	return total
}
