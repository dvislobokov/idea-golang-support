package r

type Config struct {
	Title string
	Size int
}

type Wrapper struct {
	Config
}

func build() Config {
	c := Config{Title: "a", Size: 1}
	list := []Config{{Title: "b"}, {Size: 2}}
	m := map[string]int{"Name": 1}
	w := Wrapper{}
	_ = w.Title
	c.Title = list[0].Title
	_ = m
	return c
}
