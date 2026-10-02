package p

func two() (int, string) { return 0, "" }

func f() {
	a, <caret>b := two()
	_ = a
}
