// Package report turns orders into text. It is here for the checks of completion (section 10 of README.md): its names are to be
// found from the other packages of the module by themselves, `Summar` gives `report.Summary`.
package report

import (
	"fmt"

	"example.com/playground/store"
)

// MaxLines is how many lines a report may have.
const MaxLines = 50

// Options say how a report looks.
type Options struct {
	Title    string
	Currency string
	Lines    int
	Verbose  bool
}

// Summary is one line about an order.
func Summary(order *store.Order, options Options) string {
	return fmt.Sprintf("%s: %d %s", options.Title, order.Total(), options.Currency)
}

// Describe is a title with a number of lines.
func Describe(title string, lines int) string {
	return fmt.Sprintf("%s (%d lines)", title, lines)
}
