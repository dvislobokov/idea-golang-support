package labels

func f(xs [][]int) {
/*def*/ outer:
	for _, row := range xs {
		for _, v := range row {
			if v == 0 {
				continue /*ref*/ outer
			}
			if v < 0 {
				break /*ref*/ outer
			}
		}
	}
	goto /*ref*/ done
/*def*/ done:
	return
}

func g() {
	goto /*no ref*/ outer
}
