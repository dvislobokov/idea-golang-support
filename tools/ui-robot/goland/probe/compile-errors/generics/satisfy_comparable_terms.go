package probe

func stG[_ interface{ interface{ comparable; ~int | ~string } }]() {}

func stUse[P comparable]() {
	// want: P does not satisfy interface{interface{comparable; ~int | ~string}}
	_ = stG[P]
}
