package p

func two() (int, string) { return 0, "" }

func f() {
	a, _ := two()
	_ = a
}
