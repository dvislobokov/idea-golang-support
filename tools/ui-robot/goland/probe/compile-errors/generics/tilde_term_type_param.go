package probe

// want: type in term ~A cannot be a type parameter
type tlTilde[A any] interface{ ~A }
