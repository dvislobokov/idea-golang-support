package probe

type bcA10 = [10]int

// want: invalid receiver type bcA10
func (bcA10) m() {}
