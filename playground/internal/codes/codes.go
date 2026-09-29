// Package codes stands in for google.golang.org/grpc/codes, which the playground does not require: the checks of section 11 of
// README.md need a file that reads as one of a gRPC service.
package codes

// Code is the code of a status.
type Code int

// The codes the checks speak of.
const (
	OK Code = iota
	InvalidArgument
	NotFound
	Internal
)
