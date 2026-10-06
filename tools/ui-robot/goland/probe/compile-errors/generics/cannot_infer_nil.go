package probe

func ciOne[A any](a A) {}

func ciUse() {
	// want: in call to ciOne, cannot infer A
	ciOne(nil)
}
