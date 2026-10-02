package r

type Config struct {
	Na<caret>me string
	Size int
}

type Wrapper struct {
	Config
}

func build() Config {
	c := Config{Name: "a", Size: 1}
	list := []Config{{Name: "b"}, {Size: 2}}
	m := map[string]int{"Name": 1}
	w := Wrapper{}
	_ = w.Name
	c.Name = list[0].Name
	_ = m
	return c
}
