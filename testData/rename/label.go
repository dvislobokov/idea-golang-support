package r

func loop(n int) {
ou<caret>ter:
	for i := 0; i < n; i++ {
		for j := 0; j < n; j++ {
			if j > i {
				continue outer
			}
			if j == 3 {
				break outer
			}
		}
	}
}
