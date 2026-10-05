package printf

import (
	"fmt"
	"log"
	"os"
	"testing"
)

type named struct{ Name string }

func (n named) String() string { return n.Name }

type code int

func (c code) Error() string { return "code" }

type point struct{ X, Y int }

func wrongTypes(s string, i int, f float64, err error, fn func()) {
	fmt.Printf("<weak_warning descr="fmt.Printf format %d has arg s of wrong type string">%d</weak_warning>\n", s)
	fmt.Printf("<weak_warning descr="fmt.Printf format %s has arg i of wrong type int">%s</weak_warning>\n", i)
	_ = fmt.Sprintf("<weak_warning descr="fmt.Sprintf format %t has arg f of wrong type float64">%t</weak_warning>", f)
	fmt.Printf("<weak_warning descr="fmt.Printf format %-5q has arg f of wrong type float64">%-5q</weak_warning>|\n", f)
	_ = fmt.Errorf("bad: <weak_warning descr="fmt.Errorf format %w has arg i of wrong type int">%w</weak_warning>", i)
	fmt.Printf("<weak_warning descr="fmt.Printf format %v arg fn is a func value, not called">%v</weak_warning>\n", fn)
	fmt.Fprintf(os.Stderr, "<weak_warning descr="fmt.Fprintf format %d has arg s of wrong type string">%d</weak_warning>", s)
	log.Printf("<weak_warning descr="log.Printf format %d has arg s of wrong type string">%d</weak_warning>", s)
	_ = err
}

func counts(s string, i int) {
	fmt.Printf("%d <weak_warning descr="No argument for verb: argument index = 2, arguments count = 1 (%d)">%d</weak_warning>\n", i)
	fmt.Printf("%d\n", i, <weak_warning descr="fmt.Printf call needs 1 arg but has 2 args">s</weak_warning>)
	fmt.Printf("hello\n", <weak_warning descr="fmt.Printf call has arguments but no formatting directives">i</weak_warning>)
	fmt.Printf("<weak_warning descr="fmt.Printf format has invalid argument index [3]">%[3]d</weak_warning>\n", i)
	fmt.Printf("<weak_warning descr="fmt.Printf format %*d uses non-int s as argument of *">%*d</weak_warning>\n", s, i)
}

func malformed(s string, i int, err error) {
	fmt.Printf("<weak_warning descr="fmt.Printf format %z has unknown verb z">%z</weak_warning>\n", i)
	fmt.Printf("<weak_warning descr="fmt.Printf format %#s has unrecognized flag #">%#s</weak_warning>\n", s)
	fmt.Printf("%d <weak_warning descr="fmt.Printf format % is missing verb at end of string">%</weak_warning>", i)
	fmt.Printf("<weak_warning descr="fmt.Printf does not support error-wrapping directive %w">%w</weak_warning>\n", err)
}

func printLike(i int) {
	fmt.Println("<weak_warning descr="fmt.Println call has possible Printf formatting directive %d">%d</weak_warning> items", i)
	fmt.Println("done<weak_warning descr="fmt.Println arg list ends with redundant newline">\n</weak_warning>")
	fmt.Println("100%", "a%20b", i)
}

func methods(t *testing.T, l *log.Logger, tb testing.TB, s string, i int) {
	t.Errorf("<weak_warning descr="(*testing.common).Errorf format %d has arg s of wrong type string">%d</weak_warning>", s)
	l.Printf("<weak_warning descr="(*log.Logger).Printf format %s has arg i of wrong type int">%s</weak_warning>", i)
	tb.Logf("<weak_warning descr="(testing.TB).Logf format %d has arg s of wrong type string">%d</weak_warning>", s)
}

func logf(format string, args ...any) {
	log.Printf(format, args...)
}

type logger struct{}

func (l *logger) Infof(format string, args ...any) {
	logf(format, args...)
}

// Not a wrapper: the format is changed before it is passed on.
func (l *logger) Debugf(format string, args ...any) {
	fmt.Printf(format+"\n", args...)
}

func keep(f string, a ...any) {}

// Not a wrapper: forwards to a function that is not printf-like.
func sink(format string, args ...any) {
	keep(format, args...)
}

func wrappers(l *logger, s string) {
	logf("<weak_warning descr="printf.logf format %d has arg s of wrong type string">%d</weak_warning>", s)
	l.Infof("<weak_warning descr="(*printf.logger).Infof format %d has arg s of wrong type string">%d</weak_warning>", s)
	l.Debugf("%d", s)
	sink("%d", s)
}

type rec struct{ v int }

func (r rec) String() string {
	return fmt.Sprintf("<weak_warning descr="fmt.Sprintf format %v with arg r causes recursive String method call">%v</weak_warning>", r)
}

func (r rec) Values() string {
	return fmt.Sprintf("%v %d", r, r.v)
}

func fine(s string, i int, f float64, b bool, p *int, err error, xs []byte, n named, c code, pt point, m map[string]int, args []any) {
	fmt.Printf("%s %v %s %v\n", n, n, err, c)
	fmt.Printf("%s\n", c)
	fmt.Printf("%v %v %v %v %v %+v %#v %T\n", s, i, f, b, p, pt, pt, xs)
	fmt.Printf("%[1]d %[1]x %[2]s\n", i, s)
	fmt.Printf("%*d %-*s|\n", 5, i, i, s)
	fmt.Printf("%.*f\n", 2, f)
	fmt.Printf("%x %X %x %q %c %U\n", s, xs, i, 'a', 'a', 'a')
	fmt.Printf("%p %d %s %q\n", p, p, xs, s)
	fmt.Printf("%.2f %e %g %08.3f\n", f, f, f, f)
	fmt.Printf("%t\n", b)
	fmt.Printf("%d%%\n", i)
	fmt.Printf("%v %d\n", m, m)
	fmt.Printf("%d %d\n", args...)
	fmt.Printf(s, i)
	_ = fmt.Errorf("x: %w", err)
	_ = fmt.Errorf("x: %w, %w", err, c)
	_ = fmt.Errorf("x: %w", nil)
	fmt.Println(s, i, n)
	const format = "%d items\n"
	fmt.Printf(format, i)
}
