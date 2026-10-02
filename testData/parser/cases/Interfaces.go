// Exercises interface declarations: methods, embedded interfaces, type sets with ~,
// unions, any, comparable, generic interfaces, inline interface constraints. Type-checks.
package cases

import "io"

type Empty interface{}

type Any = any

type Methods interface {
	Read(p []byte) (n int, err error)
	Close() error
	Do(f func(int) bool, args ...any)
}

type Embedded interface {
	io.Reader
	Methods
	fmtStringer
}

type fmtStringer interface{ String() string }

type Ints interface {
	~int | ~int8 | ~int16
}

type Mixed interface {
	~string | []byte
	comparable
}

type Cmp interface{ comparable }

type Generic[T any] interface {
	Get() T
	Put(T)
}

type Self[A Self[A]] interface{ Next() A }

type Union interface {
	int | string | *float64 | chan int
}

func use[T interface{ ~int | ~string }, U Generic[T]](t T, u U) {}
