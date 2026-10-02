package p

func f(a, b int) (int, int, error) {
	return a,
		b, nil
}

func g() (int, error) {
	return 1, nil
}
