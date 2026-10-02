package p

type Reader interface {
	Read(p []byte) (n int, err error)
}

type ReadCloser interface {
	Reader
	Close() error // closes
}

type Empty interface{}

type Small interface{ M() }
