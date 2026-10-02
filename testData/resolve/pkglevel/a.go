package pkglevel

type /*def*/ T struct{ /*def*/ F int }

func (/*def*/ t *T) /*def*/ M() int { return /*ref*/ t./*ref*/ F }

const /*def*/ C = 1

var /*def*/ V = /*ref*/ C + /*ref*/ helper()
