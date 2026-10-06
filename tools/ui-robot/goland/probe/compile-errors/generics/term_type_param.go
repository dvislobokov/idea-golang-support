package probe

// want: term cannot be a type parameter
type ttTerm[A any] interface{ A | int }
