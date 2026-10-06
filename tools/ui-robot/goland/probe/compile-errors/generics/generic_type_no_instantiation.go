package probe

type gtList[T any] []T

func gtUse() {
	// want: cannot use generic type gtList[T any] without instantiation
	var l gtList
	_ = l
}
