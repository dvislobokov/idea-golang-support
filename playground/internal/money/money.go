// Package money formats amounts. An internal package: its names are offered inside this module only (section 10 of README.md).
package money

import "fmt"

type Same struct {
}

type Hello struct {
}

// Format is an amount of cents as `12.50 EUR`.
func Format(cents int, currency string) string {

	return fmt.Sprintf("%d.%02d %s", cents/100, cents%100, currency)
}
