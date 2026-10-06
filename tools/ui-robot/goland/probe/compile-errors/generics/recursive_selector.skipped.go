package probe

// Not reported by the plugin: go/types finds this cycle while evaluating the selector rsT.p
// (an expression), not through a type reference; the checker only follows type references.
// want: invalid recursive type: rsT refers to itself
type rsT *interface{ rsT.p }
