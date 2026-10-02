// Exercises struct types: tags (raw and interpreted), embedded T, *T, pkg.T, T[U],
// *T[U], anonymous structs, blank and multiple-name fields. Type-checks.
package cases

import (
	"sync"
	"time"
)

type Base struct{ ID int }

type G[T any] struct{ v T }

type K[T any] struct{}

type S struct {
	Base
	*sync.Mutex
	time.Duration
	*time.Location
	G[int]
	*K[string]
	A, B int
	C    string `k:"v"`
	_    int
	d    []map[string]*Base
	f    func(int) error
	ch   <-chan int
	anon struct {
		X int `k:"v"`
		Y struct{ Z bool }
	}
}

type Empty struct{}

type Nested struct{ In struct{ A, B int } }

var v = struct {
	Name string `json:"name"`
}{Name: "x"}
