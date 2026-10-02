package r

func loop(n int) {
rows:
	for i := 0; i < n; i++ {
		for j := 0; j < n; j++ {
			if j > i {
				continue rows
			}
			if j == 3 {
				break rows
			}
		}
	}
}
