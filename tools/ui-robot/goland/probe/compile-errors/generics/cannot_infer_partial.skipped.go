package probe

func pcF[A, B any](a A) {}

// Not reported by the plugin: go/types reports the parameter it cannot infer together with its
// declaration position, which the checker does not reproduce; nothing is reported instead.
// want: cannot infer B (declared at ./cannot_infer_partial.skipped.go:3:13)
var pcValue = pcF[int]
