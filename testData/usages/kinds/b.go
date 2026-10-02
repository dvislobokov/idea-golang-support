package kinds

func other() {
	var p Point
	p.Move(1)
	_ = NewPoint(2).X
	_ = helper()
	// A local with the same name as a field: must not be a usage of Point.X.
	X := 1
	_ = X
}
