package probe

type scT1[P interface{ ~uint }] struct{}

func scUse[P any]() {
	// want: P does not satisfy interface{~uint}
	_ = scT1[P]{}
}
