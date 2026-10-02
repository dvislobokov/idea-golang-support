package r

type Reader interface {
	Read(p []byte) int
}

type File struct{}

func (f *File) Fetch(p []byte) int { return 0 }

type Buffer struct{}

func (b Buffer) Read(p []byte) int { return len(p) }

// Other has an unrelated Read with another signature: not renamed.
type Other struct{}

func (Other) Read() {}

func use(r Reader, f *File, o Other) int {
	o.Read()
	return r.Read(nil) + f.Fetch(nil) + Buffer{}.Read(nil)
}
