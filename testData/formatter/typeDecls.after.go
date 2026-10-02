package p

type (
	ID       int
	Name     string
	Callback func(int) error
	Alias    = int
)

type Single struct {
	F int
}

type Point struct{ X, Y int }
