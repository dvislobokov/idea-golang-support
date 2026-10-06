package recursiveiface

// Self-embedding interfaces: the checker reports them, and every predicate over them must terminate (seen live: StackOverflowError in hasTypeTerms).

type irSelf interface{ irSelf } // ERROR "invalid recursive type: irSelf refers to itself"

type irGen[A any] interface{ irGen[A] } // ERROR "invalid recursive type: irGen[A] refers to itself"

var _ irSelf
var _ irGen[int]

func useIr(x irSelf, y irGen[int]) bool {
	var z interface{} = x
	_ = z
	_ = new(irGen[string])
	return x == nil || y == nil
}
