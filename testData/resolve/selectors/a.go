package selectors

type /*def*/ Base struct{ /*def*/ ID int }

func (b Base) /*def*/ Name() string { return "" }
func (b *Base) /*def*/ SetID(id int) { b./*ref*/ ID = id }

type /*def*/ Outer struct {
	/*def:BaseField*/ Base
	/*def*/ Extra string
	/*def*/ inner struct{ /*def*/ Deep int }
}

type /*def*/ Iface interface {
	/*def*/ Name() string
	/*def*/ Close() error
}

func use(o Outer, p *Outer, i Iface, pp **Outer) {
	_ = o./*ref*/ ID
	_ = o./*ref*/ Extra
	_ = o./*ref*/ Name()
	o./*ref*/ SetID(1)
	_ = p./*ref*/ ID
	_ = p./*ref:BaseField*/ Base./*ref*/ ID
	_ = o./*ref*/ inner./*ref*/ Deep
	_ = i./*ref:Name*/ Name
	_ = i./*ref*/ Close
	f := o./*ref*/ Name
	_ = f
	g := Outer./*ref*/ Name
	_ = g
	h := (*Outer)./*ref*/ SetID
	_ = h
	_ = pp./*no ref*/ ID
	_ = o./*no ref*/ Missing
}
