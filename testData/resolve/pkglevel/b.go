package pkglevel

func /*def*/ helper() int {
	var t /*ref*/ T
	_ = t./*ref*/ M()
	_ = /*ref*/ V
	return /*ref*/ C
}

func init() { _ = /*no ref*/ init }
