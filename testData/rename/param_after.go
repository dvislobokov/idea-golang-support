package r

func scale(x int, factor int) int {
	if x < 0 {
		x = -x
	}
	return x * factor
}

func use(value int) int { return scale(value, 2) }
