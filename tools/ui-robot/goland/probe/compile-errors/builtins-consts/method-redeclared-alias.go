package probe

type bcT0 struct{}

type bcA0 = bcT0

func (bcT0) m1() {}

// want: method bcT0.m1 already declared at <position of the first m1> (the plugin omits the position)
func (bcA0) m1() {}
