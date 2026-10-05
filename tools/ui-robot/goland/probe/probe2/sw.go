package probe2

type Color int

const (
	Red Color = iota
	Green
	Blue
	Cyan
)

type Flag uint

const (
	FlagA Flag = 1 << iota
	FlagB
	FlagC
)

func pick(c Color, f Flag) {
	switch c {
	case Red:
	}
	switch f {
	case FlagA:
	}
	switch c {
	case Green:
	default:
	}
}

type Mixed struct {
	UserName string `json:"user_name"`
	Email    string
	Age      int `xml:"age"`
}
