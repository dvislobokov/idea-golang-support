package r

func scale(v<caret>alue int, factor int) int {
	if value < 0 {
		value = -value
	}
	return value * factor
}

func use(value int) int { return scale(value, 2) }
