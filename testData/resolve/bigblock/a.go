package bigblock

// Blocks with more statements than GoScopes.INDEXED_STATEMENTS resolve locals through a cached
// name index instead of walking every preceding statement; the visibility rules are the same.

func /*def*/ big(/*def*/ p int) int {
	/*def:x1*/ x := /*ref*/ p
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	/*ref:x1*/ x = 2
	_ = /*no ref*/ later
	var (
		/*def*/ g1 = /*ref:x1*/ x
		/*def*/ g2 = /*ref*/ g1
	)
	type /*def*/ node struct{ next */*ref*/ node }
	{
		/*def:x2*/ x := /*ref:x1*/ x
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = /*ref:x2*/ x
	}
	switch /*ref:x1*/ x {
	case 1:
		/*def:c1*/ c := 1
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = 0
		_ = /*ref:c1*/ c
	}
	_ = /*no ref*/ c
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	_ = 0
	/*def*/ later := /*ref*/ g2
	var /*def*/ n /*ref*/ node
	_ = /*ref*/ n
	_ = /*ref*/ later
	return /*ref*/ big(/*ref:x1*/ x)
}
