package inference

import (
	"cmp"
	"iter"
	"maps"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
)

type Number interface{ ~int | ~int64 | ~float64 }

func Identity[T any](x T) T                       { return x }
func First[T any](xs []T) T                       { var z T; return z }
func Pair[A, B any](a A, b B) struct{ A A; B B }  { return struct{ A A; B B }{a, b} }
func MapSlice[A, B any](xs []A, f func(A) B) []B  { return nil }
func Keys[K comparable, V any](m map[K]V) []K     { return nil }
func Sum[T Number](xs ...T) T                     { var s T; return s }
func Ptr[T any](v T) *T                           { return &v }
func Apply[T any](f func(T) T, x T) T             { return f(x) }
func Reduce[T, R any](xs []T, init R, f func(R, T) R) R { return init }
func SortSlice[S ~[]E, E cmp.Ordered](s S) S      { return s }
func Filter[S ~[]E, E any](s S, pred func(E) bool) S { return s }
func Recv[T any](ch <-chan T) T                   { return <-ch }
func Deref[T any](p *T) T                         { return *p }
func Call[T any](f func() T) T                    { return f() }
func Zip[K comparable, V any](ks []K, vs []V) map[K]V { return nil }
func MinOf[T cmp.Ordered](a, b T) T               { if a < b { return a }; return b }
func Must[T any](v T, err error) T                { return v }
func Two[A, B any](a A, b B) (A, B)               { return a, b }
func Compose[A, B, C any](f func(A) B, g func(B) C) func(A) C { return nil }

type List[T any] struct{ items []T }

func (l *List[T]) Push(v T)                 {}
func (l *List[T]) Head() T                  { var z T; return z }
func (l *List[T]) Map(f func(T) T) *List[T] { return l }
func NewList[T any](xs ...T) *List[T]       { return &List[T]{xs} }

type Box[T any] struct{ V T }

func (b Box[T]) Get() T      { return b.V }
func Wrap[T any](v T) Box[T] { return Box[T]{v} }

type MyInts []int
type MyString string

func pairInt() (int, error) { return 0, nil }

func _() {
	var ints []int
	var strs []string
	var m map[string]int
	var mi MyInts
	var ms MyString
	var ch chan float64
	_ = Identity(1) /*T: int*/
	_ = Identity("s") /*T: string*/
	_ = Identity(1.5) /*T: float64*/
	_ = Identity('x') /*T: rune*/
	_ = Identity(ms) /*T: MyString*/
	_ = Identity(ints) /*T: []int*/
	_ = Identity[int64](1) /*T: int64*/
	_ = First(ints) /*T: int*/
	_ = First(strs) /*T: string*/
	_ = First(mi) /*T: int*/
	_ = First([]Box[int]{}) /*T: Box[int]*/
	_ = Pair(1, "a") /*T: struct{A int; B string}*/
	_ = Pair(ints, m) /*T: struct{A []int; B map[string]int}*/
	_ = MapSlice(ints, func(i int) string { return "" }) /*T: []string*/
	_ = MapSlice(strs, strings.ToUpper) /*T: []string*/
	_ = MapSlice(ints, Identity[int]) /*T: []int*/
	_ = Keys(m) /*T: []string*/
	_ = Keys(map[int]bool{}) /*T: []int*/
	_ = Sum(1, 2, 3) /*T: int*/
	_ = Sum(1.5, 2) /*T: float64*/
	_ = Sum(ints...) /*T: int*/
	_ = Sum[int64]() /*T: int64*/
	_ = Ptr(1) /*T: *int*/
	_ = Ptr(ms) /*T: *MyString*/
	_ = Deref(Ptr("a")) /*T: string*/
	_ = Apply(func(x int) int { return x }, 1) /*T: int*/
	_ = Apply(strings.TrimSpace, " a ") /*T: string*/
	_ = Reduce(ints, "", func(acc string, x int) string { return acc }) /*T: string*/
	_ = Reduce(strs, 0.5, func(acc float64, s string) float64 { return acc }) /*T: float64*/
	_ = SortSlice(ints) /*T: []int*/
	_ = SortSlice(mi) /*T: MyInts*/
	_ = Filter(mi, func(i int) bool { return true }) /*T: MyInts*/
	_ = Recv(ch) /*T: float64*/
	_ = Call(func() []string { return nil }) /*T: []string*/
	_ = Zip(strs, ints) /*T: map[string]int*/
	_ = MinOf(1, 2) /*T: int*/
	_ = MinOf("a", "b") /*T: string*/
	_ = MinOf(ms, "b") /*T: MyString*/
	_ = Must(pairInt()) /*T: int*/
	_ = Two(1, "a") /*T: (int, string)*/
	_ = Compose(strings.TrimSpace, func(s string) int { return 0 }) /*T: func(string) int*/
	l := NewList(1, 2) /*T: *List[int]*/
	_ = l.Head() /*T: int*/
	_ = l.Map(func(i int) int { return i }) /*T: *List[int]*/
	_ = NewList("a").Head() /*T: string*/
	_ = NewList[float64]().Head() /*T: float64*/
	_ = Wrap(ms).Get() /*T: MyString*/
	_ = Wrap(ints).V /*T: []int*/
	var b Box[string]
	_ = b.Get() /*T: string*/
	_ = Identity(b).V /*T: string*/
	_ = First(NewList(1).items) /*T: int*/
	var f func(int) int = Identity[int]
	_ = f /*T: func(int) int*/
	// standard library
	_ = slices.Index(ints, 1) /*T: int*/
	_ = slices.Contains(strs, "a") /*T: bool*/
	_ = slices.Sorted(maps.Keys(m)) /*T: []string*/
	_ = slices.Collect(maps.Values(m)) /*T: []int*/
	_ = maps.Keys(m) /*T: Seq[string]*/
	_ = slices.Max(ints) /*T: int*/
	_ = slices.Clone(mi) /*T: MyInts*/
	_ = cmp.Compare(1, 2) /*T: int*/
	_ = sync.OnceValue(func() int { return 1 }) /*T: func() int*/
	var ap atomic.Pointer[Box[int]]
	_ = ap.Load() /*T: *Box[int]*/
	var seq iter.Seq[int]
	_ = slices.Collect(seq) /*T: []int*/
	for v := range maps.Values(m) {
		_ = v /*T: int*/
	}
	_ = slices.IndexFunc(ints, func(i int) bool { return true }) /*T: int*/
	_ = slices.SortedFunc(slices.Values(strs), strings.Compare) /*T: []string*/
}
