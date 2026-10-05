package p

import "errors"

type Empty struct <fold text='{}' expand='false'>{
}</fold>

type Any interface <fold text='{}' expand='false'>{
}</fold>

type Point struct <fold text='{...}' expand='true'>{
	X int
}</fold>

func noop() <fold text='{}' expand='false'>{
}</fold>

func answer() int <fold text='{ return 42 }' expand='false'>{
	return 42
}</fold>

func (p Point) Get() int <fold text='{ return p.X }' expand='false'>{
	return p.X
}</fold>

func load(name string) (string, error) <fold text='{...}' expand='true'>{
	data, err := read(name)
	if err != nil <fold text='{ return "", err }' expand='false'>{
		return "", err
	}</fold>
	if err := check(data); err != nil <fold text='{ panic(err) }' expand='false'>{
		panic(err)
	}</fold>
	if data == "" <fold text='{...}' expand='true'>{
		return "", nil
	}</fold>
	if err != nil <fold text='{...}' expand='true'>{
		data = ""
	}</fold>
	if err != nil <fold text='{...}' expand='true'>{
		// a comment keeps the block open
		return "", err
	}</fold>
	f := func() error <fold text='{ return errors.New("a very long message that does not fit in... }' expand='false'>{
		return errors.New("a very long message that does not fit in the placeholder")
	}</fold>
	_ = f
	switch name <fold text='{...}' expand='true'>{
	case "a":<fold text=' return "A", nil' expand='false'>
		return "A", nil</fold>
	case "b":
		data = "b"
		return data, nil
	default:<fold text=' data = name' expand='false'>
		data = name</fold>
	}</fold>
	return data, nil
}</fold>

func read(name string) (string, error) { return name, nil }

func check(string) error { return nil }
