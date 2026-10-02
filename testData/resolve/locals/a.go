package locals

func /*def*/ outer(/*def*/ p int) int {
	/*def*/ x := /*ref*/ p + 1
	if /*def:y1*/ y := /*ref*/ x; /*ref:y1*/ y > 0 {
		/*def:y2*/ y := /*ref:y1*/ y * 2
		_ = /*ref:y2*/ y
	} else {
		_ = /*ref:y1*/ y
	}
	for /*def*/ i := 0; /*ref*/ i < 3; /*ref*/ i++ {
		/*def:x2*/ x := /*ref*/ i
		_ = /*ref:x2*/ x
	}
	{
		var /*def*/ z = /*ref*/ x
		_ = /*ref*/ z
	}
	_ = /*no ref*/ z
	_ = /*no ref*/ later
	/*def*/ later := 1
	_ = /*ref*/ later
	type /*def*/ node struct{ next */*ref*/ node }
	var /*def*/ n /*ref*/ node
	_ = /*ref*/ n
	return /*ref*/ outer(/*ref*/ x)
}

var /*def*/ v = func(/*def*/ a int) int { return /*ref*/ a }

func shadow() {
	/*def*/ outer := 2
	_ = /*ref*/ outer
	_ = /*ref*/ v
}
