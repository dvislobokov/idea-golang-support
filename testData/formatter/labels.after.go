package p

func f(n int) {
outer:
	for i := 0; i < n; i++ {
	inner:
		for j := 0; j < n; j++ {
			if j == i {
				continue outer
			}
			if j > 10 {
				break inner
			}
		}
	}
	goto end
end:
}
