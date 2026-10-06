package probe

func bcPair() (int, string) { return 0, "" }

func bcAppend() {
	// want: invalid append: argument must be a slice; have 1st function result (value of type int)
	_ = append(bcPair())
}
