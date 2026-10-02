// Stub coverage: interface method specs, embedded interfaces, type sets, function types.
package ifaces

import "io"

type Reader interface {
	Read(p []byte) (n int, err error)
}

type ReadCloser interface {
	io.Reader
	Reader
	Close() error
	Each(f func(int) bool, args ...any)
}

type Ordered interface {
	~int | ~string | float64
}

type Handler func(w io.Writer, r <-chan string) error

type Ch chan<- map[string][]*int

func (h Handler) Serve() {}
