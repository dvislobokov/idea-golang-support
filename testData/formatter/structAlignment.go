package p

type Config struct {
Name string `json:"name"`
LongFieldName int `json:"long_field_name,omitempty"`
X, Y float64 // coordinates
	Enabled bool // whether enabled
Inner struct {
A int
BB string
}
embedded
*Pointer `tag:"p"`

AfterBlank int // new section
Z int
}

type embedded struct{}
type Pointer struct{ v int }
