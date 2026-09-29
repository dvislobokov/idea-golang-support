// Package status stands in for google.golang.org/grpc/status, see the package codes next to it.
package status

import (
	"fmt"

	"example.com/playground/internal/codes"
)

// Error is an error with a code.
func Error(c codes.Code, message string) error {
	return fmt.Errorf("code %d: %s", c, message)
}

// Errorf is an error with a code and a formatted message.
func Errorf(c codes.Code, format string, a ...any) error {
	return Error(c, fmt.Sprintf(format, a...))
}
