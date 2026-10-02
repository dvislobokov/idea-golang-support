package exprs

import "strings"

type T struct {
	A int
	B *T
	S []string
	M map[string]float64
}

func (t T) Val() int        { return 0 }
func (t *T) Ptr() (int, error) { return 0, nil }

type I interface{ Val() int }

type MyInt int

const (
	C0 = iota /*T: untyped int*/
	C1
	C2 MyInt = iota * 10
	C3
)

const S = "str" /*T: untyped string*/
const F = 1.5 /*T: untyped float*/
const R = 'x' /*T: untyped rune*/
const Cx = 2i /*T: untyped complex*/
const B = 1 < 2 /*T: untyped bool*/

var (
	a = 1 /*T: untyped int*/
	b int = 2
	c = a /*T: int*/ + b /*T: int*/
	d = 1.5 * 2 /*T: untyped float*/
	d2 = 2 /*T: untyped int*/
	e = T{} /*T: T*/
	f = &T{} /*T: *T*/
	g = []int{1, 2} /*T: []int*/
	h = map[string][]int{} /*T: map[string][]int*/
	i = [...]int{1, 2, 3} /*T: [3]int*/
	j = [2][3]byte{} /*T: [2][3]byte*/
	k = e.A /*T: int*/
	l = f.B.B.A /*T: int*/
	m = e.S[0] /*T: string*/
	n = e.M["x"] /*T: float64*/
	o = e.S[1:] /*T: []string*/
	p = "abc"[1] /*T: byte*/
	q = "abc"[1:] /*T: string*/
	r = e.Val /*T: func() int*/
	s = e.Val() /*T: int*/
	t2 = f.Ptr() /*T: (int, error)*/
	u = T.Val /*T: func(T) int*/
	v = (*T).Ptr /*T: func(*T) (int, error)*/
	w = len(g) /*T: int*/
	x = append(g, 3) /*T: []int*/
	y = make(chan int, 1) /*T: chan int*/
	z = new(T) /*T: *T*/
	aa = <-y /*T: int*/
	bb = *f /*T: T*/
	cc = &g /*T: *[]int*/
	dd = !true /*T: untyped bool*/
	ee = -a /*T: int*/
	ff = C2 /*T: MyInt*/
	gg = C3 /*T: MyInt*/
	hh = C1 /*T: untyped int*/
	ii = MyInt(3) /*T: MyInt*/
	jj = []byte("x") /*T: []byte*/
	kk = strings.ToUpper("x") /*T: string*/
	ll = strings.Split("a,b", ",") /*T: []string*/
	mm = func(x int) string { return "" } /*T: func(x int) string*/
	nn = mm(1) /*T: string*/
	oo = any(1) /*T: interface{}*/
	pp = I(e) /*T: I*/
	qq = oo.(T) /*T: T*/
	rr = 1 << 3 /*T: untyped int*/
	ss = b << 2 /*T: int*/
	tt = a == b /*T: untyped bool*/
	uu = complex(1.0, 2.0) /*T: untyped complex*/
	vv = real(uu) /*T: float64*/
	ww = min(1, b) /*T: int*/
	xx = max(1.5, 2) /*T: untyped float*/
	yy = nil /*T: untyped nil*/
	zz = struct{ X int }{1} /*T: struct{X int}*/
)

func ranges(arr [3]string, sl []T, mp map[int]bool, ch chan string, seq func(yield func(int, string) bool)) {
	for i, v := range arr {
		_ = i /*T: int*/
		_ = v /*T: string*/
	}
	for _, v := range sl {
		_ = v /*T: T*/
	}
	for k, v := range mp {
		_ = k /*T: int*/
		_ = v /*T: bool*/
	}
	for v := range ch {
		_ = v /*T: string*/
	}
	for i := range 10 {
		_ = i /*T: int*/
	}
	for i, s := range "héllo" {
		_ = i /*T: int*/
		_ = s /*T: rune*/
	}
	for k, v := range seq {
		_ = k /*T: int*/
		_ = v /*T: string*/
	}
	x, ok := mp[1]
	_ = x /*T: bool*/
	_ = ok /*T: bool*/
	val, err := f.Ptr()
	_ = val /*T: int*/
	_ = err /*T: error*/
	var iface I = e
	switch s := iface.(type) {
	case T:
		_ = s /*T: T*/
	case *T:
		_ = s /*T: *T*/
	default:
		_ = s /*T: I*/
	}
	defer func() { recover() }()
	ptr := &sl[0]
	_ = ptr /*T: *T*/
	_ = ptr.A /*T: int*/
	_ = (*ptr).S /*T: []string*/
}

type List[T any] struct{ items []T }

func (l *List[T]) First() T { var z T; return z }

func Map[A, B any](xs []A, f func(A) B) []B { return nil }

func generics() {
	var l List[string]
	_ = l /*T: List[string]*/
	_ = l.First() /*T: string*/
	_ = l.items /*T: []string*/
	_ = Map[int, string] /*T: func(xs []int, f func(int) string) []string*/
	_ = Map[int, string](nil, nil) /*T: []string*/
	_ = Map([]int{1}, func(i int) bool { return true }) /*T: []bool*/
	p := &List[float64]{}
	_ = p.First() /*T: float64*/
}

func Values[Slice ~[]E, E any](s Slice) func(yield func(E) bool) { return nil }

func Keys[Map ~map[K]V, K comparable, V any](m Map) func(yield func(K) bool) { return nil }

type Block struct{ ID int }

func constraintInference(blocks []*Block, m map[string]float64) {
	for b := range Values(blocks) {
		_ = b /*T: *Block*/
	}
	for k := range Keys(m) {
		_ = k /*T: string*/
	}
	_ = Values(blocks) /*T: func(yield func(*Block) bool)*/
}

// The spelling of byte/rune versus uint8/int32 is kept by the declaration.
func aliasSpelling(b byte, u uint8, r rune, i32 int32, bs []byte, us []uint8, rs []rune) {
	_ = b /*T: byte*/
	_ = u /*T: uint8*/
	_ = r /*T: rune*/
	_ = i32 /*T: int32*/
	_ = bs /*T: []byte*/
	_ = us /*T: []uint8*/
	_ = rs /*T: []rune*/
	_ = map[byte]rune{} /*T: map[byte]rune*/
	_ = &b /*T: *byte*/
}
