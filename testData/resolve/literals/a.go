package literals

type /*def*/ Point struct{ /*def*/ X, /*def*/ Y int }

type /*def*/ Line struct {
	/*ref*/ Point
	/*def*/ End Point
	/*def*/ Tags map[string]int
}

var p = Point{/*ref*/ X: 1, /*ref*/ Y: 2}

var l = Line{
	Point: Point{/*ref*/ X: 1},
	/*ref*/ End:   Point{/*ref*/ Y: 3},
	/*ref*/ Tags:  map[string]int{"a": 1},
}

var promoted = Line{/*ref*/ X: 1}

var lines = []Line{{/*ref*/ End: Point{/*ref*/ X: 1}}, {/*ref*/ Tags: nil}}

var byKey = map[string]Point{"k": {/*ref*/ X: 1}}

var arr = [...]Point{0: {/*ref*/ X: 1}, 2: {}}

const /*def*/ idx = 1

var sl = []int{/*ref*/ idx: 5}
