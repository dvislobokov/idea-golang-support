// Command check is a place to type in: every function is one check of section 10 of README.md, its number is in the comment.
// The file builds as it is; what is typed for a check is to be taken back after it (Ctrl+Z), so that the next one starts clean.
package main

import (
	"os"

	"example.com/playground/report"
	"example.com/playground/store"
)

// 10.1 - 10.4, 10.12: type on the empty line.
func byName() {
	report.Summary(&store.Order{
		Currency: "",
	}, report.Options{
		Title:    "",
		Currency: "",
		Lines:    0,
		Verbose:  false,
	})
}

// 10.5, 10.6: type `c := client` on the empty line.
func literal() {

}

// 10.7: delete `title` between the brackets and ask for completion there.
func fits(title string, lines int, verbose bool) string {
	width := 80
	_, _ = width, verbose
	return describe(title, lines)
}

func describe(title string, lines int) string {
	if lines == 0 {
		return ""
	}
	return title
}

// 10.8: delete `nil, err` and type `ni`.
func load(name string) (*os.File, error) {
	f, err := os.Open(name)
	if err != nil {
		return nil, err
	}
	return f, nil
}

// 10.9: delete the function between the brackets, from `func` to its `}`, and ask for completion there.
func sorted(names []string) {
}

// 10.10, 10.11: the Russian layout. Type on the empty line, then at the end of this comment, then between the quotes.
func layout() string {

	return ""
}

func main() {
	byName()
	literal()
	_ = fits("check", 1, false)
	_, _ = load("go.mod")
	sorted(nil)
	_ = layout()
}
