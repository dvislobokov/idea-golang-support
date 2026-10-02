package clean

import (
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

// A realistic file that must produce no diagnostics at all.

type Server struct {
	mu      sync.Mutex
	clients map[string]*Client
	log     io.Writer
	started time.Time
}

type Client struct {
	ID   string
	Conn io.ReadWriteCloser
	tags []string
}

var ErrClosed = errors.New("closed")

func NewServer(log io.Writer) *Server {
	return &Server{clients: make(map[string]*Client), log: log, started: time.Now()}
}

func (s *Server) Add(c *Client) error {
	if c == nil {
		return fmt.Errorf("nil client: %w", ErrClosed)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.clients[c.ID]; ok {
		return errors.New("duplicate")
	}
	s.clients[c.ID] = c
	fmt.Fprintf(s.log, "added %s after %v\n", c.ID, time.Since(s.started))
	return nil
}

func (s *Server) IDs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	ids := make([]string, 0, len(s.clients))
	for id := range s.clients {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

func (s *Server) Broadcast(msg string) (n int, err error) {
	for _, c := range s.clients {
		if _, e := io.WriteString(c.Conn, msg); e != nil {
			err = e
			continue
		}
		n++
	}
	return n, err
}

func (c *Client) Tagged(prefix string) []string {
	var out []string
	for _, t := range c.tags {
		if strings.HasPrefix(t, prefix) {
			out = append(out, strings.TrimPrefix(t, prefix))
		}
	}
	return out
}

func describe(v any) string {
	switch x := v.(type) {
	case nil:
		return "nil"
	case int, int64:
		return fmt.Sprint(x)
	case string:
		return x
	case error:
		return x.Error()
	case fmt.Stringer:
		return x.String()
	default:
		return fmt.Sprintf("%T", x)
	}
}

const (
	KB = 1 << (10 * (iota + 1))
	MB
	GB
)

type Level int

const (
	Debug Level = iota
	Info
	Warn
)

func (l Level) String() string {
	switch l {
	case Debug:
		return "debug"
	case Info:
		return "info"
	}
	return "warn"
}

func main() {
	srv := NewServer(os.Stdout)
	if err := srv.Add(&Client{ID: "a"}); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	var total int64
	total += int64(KB) + MB
	ratio := float64(total) / GB
	fmt.Println(srv.IDs(), ratio, describe(Info), Warn.String())
	done := make(chan struct{}, 1)
	go func() {
		defer close(done)
		done <- struct{}{}
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
	}
	var buf strings.Builder
	buf.WriteString("x")
	_ = buf.Len() > 0 && buf.String() != ""
	matrix := [2][2]int{{1, 2}, {3, 4}}
	for i := range matrix {
		for j := range matrix[i] {
			matrix[i][j] *= 2
		}
	}
	m := map[string][]int{"a": {1}}
	m["b"] = append(m["b"], 2)
	p := &matrix[0][1]
	*p++
	var w io.Writer = os.Stderr
	if f, ok := w.(*os.File); ok {
		_ = f.Name()
	}
	_ = errors.Is(ErrClosed, ErrClosed)
}

// Phase 5c corpus regressions: none of these is an error.

const goos = "linux"

func boolCases() int {
	switch {
	case goos == "windows":
		return 1
	case goos == "darwin": // equal boolean constants are not duplicate cases
		return 2
	}
	return 0
}

func interfaceShift(i uint) { fmt.Printf("%x", 1<<i) }

func convertToParam[T ~uint64](x uint64) T { return T(x) }

type table[EI ~uint64] struct{ ids []EI }

func (t *table[EI]) next() EI { return EI(len(t.ids)) + 1 } // receiver type parameter conversion

type removedInfo struct{ Removed int }

var Removed = []removedInfo{{Removed: 24}} // a field key is not an initialization dependency

type node interface{ pos() int }

type leaf struct{}

func (leaf) pos() int { return 0 }

func walkNodes(f func(node)) { f(leaf{}) }

func switchOnNodes() {
	walkNodes(func(n node) {
		switch n := n.(type) {
		case leaf:
			_ = n
		}
	})
}

func terminating(x int) int {
	for {
		if x > 0 {
			return x
		}
	}
}

// Semantic tails (0.0.9): conversion to a receiver type parameter (internal/trace/base.go).
type dataTable[EI ~uint64, E any] struct{ sparse map[EI]E }

func (d *dataTable[EI, E]) appendData(data E) EI {
	if d.sparse == nil {
		d.sparse = make(map[EI]E)
	}
	id := EI(len(d.sparse)) + 1
	d.sparse[id] = data
	return id
}

// Recursive generic calls pass the function's own type parameters (slices/zsortanyfunc.go).
func sortRec[E any](data []E, a, b int, cmp func(x, y E) int) {
	if b-a < 2 {
		return
	}
	sortRec(data, a+1, b, cmp)
}

// intersects (x/tools inline/util.go): recursion with swapped type arguments.
func intersects[K comparable, T1, T2 any](x map[K]T1, y map[K]T2) bool {
	if len(x) > len(y) {
		return intersects(y, x)
	}
	for k := range x {
		if _, ok := y[k]; ok {
			return true
		}
	}
	return false
}

// Explicit instantiation with a foreign type parameter: E inferred through S's core type (slices.Concat).
func growTo[S ~[]E, E any](s S, n int) S { return append(s, make(S, n)...) }

func concatAll[S ~[]E, E any](ss ...S) S {
	out := growTo[S](nil, len(ss))
	for _, s := range ss {
		out = append(out, s...)
	}
	return out
}
