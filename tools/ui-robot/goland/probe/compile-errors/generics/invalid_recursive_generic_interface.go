package probe

// want: invalid recursive type: irGen[A] refers to itself
type irGen[A any] interface{ irGen[A] }

func irUse() { _ = new(irGen[int]) }
