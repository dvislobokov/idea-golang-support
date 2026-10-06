package probe

var bcStrS uint

// Not reported: the shift count might be a constant the plugin cannot fold, and string(1 << c) of a
// constant shift is a valid int-to-string conversion.
// want: invalid operation: shifted operand 1 (type string) must be integer
var bcStr = string(1 << bcStrS)
