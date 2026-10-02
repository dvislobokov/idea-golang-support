package p

var tests = []struct {
	name string
	in   []int
	want map[string]int
}{
	{"empty", nil, nil},
	{
		name: "one",
		in:   []int{1},
		want: map[string]int{"a": 1, "bb": 2},
	},
}
