package probe2

type Mixed struct {
	UserName string `json:"user_name"`
	Email    string
	Age      int `xml:"age"`
}

type Same struct {
	Value int `json:"value"`
	Other int `json:"other"`
}
