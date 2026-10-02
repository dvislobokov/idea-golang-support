// Package util holds helpers for the UI robot scenario.
package util

import "strings"

// Greet returns a greeting for name.
func Greet(name string) string {
	return "hello, " + strings.ToUpper(name)
}

// Repeat repeats s n times, separated by sep.
func Repeat(s string, n int, sep string) string {
	parts := make([]string, 0, n)
	for i := 0; i < n; i++ {
		parts = append(parts, s)
	}
	return strings.Join(parts, sep)
}
