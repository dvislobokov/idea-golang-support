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
	fmt.Printf("<warning descr="fmt.Printf format %d has arg s of wrong type string">%d</warning>\n", s)
	fmt.Printf("<warning descr="fmt.Printf format %s has arg i of wrong type int">%s</warning>\n", i)
	_ = fmt.Sprintf("<warning descr="fmt.Sprintf format %t has arg f of wrong type float64">%t</warning>", f)
	fmt.Printf("<warning descr="fmt.Printf format %-5q has arg f of wrong type float64">%-5q</warning>|\n", f)
	_ = fmt.Errorf("bad: <warning descr="fmt.Errorf format %w has arg i of wrong type int">%w</warning>", i)
	fmt.Printf("<warning descr="fmt.Printf format %v arg fn is a func value, not called">%v</warning>\n", fn)
	fmt.Fprintf(os.Stderr, "<warning descr="fmt.Fprintf format %d has arg s of wrong type string">%d</warning>", s)
	log.Printf("<warning descr="log.Printf format %d has arg s of wrong type string">%d</warning>", s)
	_ = err
}

func counts(s string, i int) {
	fmt.Printf("%d <warning descr="fmt.Printf format %d reads arg #2, but call has 1 arg">%d</warning>\n", i)
	fmt.Printf("%d\n", i, <warning descr="fmt.Printf call needs 1 arg but has 2 args">s</warning>)
	fmt.Printf("hello\n", <warning descr="fmt.Printf call has arguments but no formatting directives">i</warning>)
	fmt.Printf("<warning descr="fmt.Printf format has invalid argument index [3]">%[3]d</warning>\n", i)
	fmt.Printf("<warning descr="fmt.Printf format %*d uses non-int s as argument of *">%*d</warning>\n", s, i)
}

func malformed(s string, i int, err error) {
	fmt.Printf("<warning descr="fmt.Printf format %z has unknown verb z">%z</warning>\n", i)
	fmt.Printf("<warning descr="fmt.Printf format %#s has unrecognized flag #">%#s</warning>\n", s)
	fmt.Printf("%d <warning descr="fmt.Printf format % is missing verb at end of string">%</warning>", i)
	fmt.Printf("<warning descr="fmt.Printf does not support error-wrapping directive %w">%w</warning>\n", err)
}

func printLike(i int) {
	fmt.Println("<warning descr="fmt.Println call has possible Printf formatting directive %d">%d</warning> items", i)
	fmt.Println("done<warning descr="fmt.Println arg list ends with redundant newline">\n</warning>")
	fmt.Println("100%", "a%20b", i)
}

func methods(t *testing.T, l *log.Logger, tb testing.TB, s string, i int) {
	t.Errorf("<warning descr="(*testing.common).Errorf format %d has arg s of wrong type string">%d</warning>", s)
	l.Printf("<warning descr="(*log.Logger).Printf format %s has arg i of wrong type int">%s</warning>", i)
	tb.Logf("<warning descr="(testing.TB).Logf format %d has arg s of wrong type string">%d</warning>", s)
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
	logf("<warning descr="printf.logf format %d has arg s of wrong type string">%d</warning>", s)
	l.Infof("<warning descr="(*printf.logger).Infof format %d has arg s of wrong type string">%d</warning>", s)
	l.Debugf("%d", s)
	sink("%d", s)
}

type rec struct{ v int }

func (r rec) String() string {
	return fmt.Sprintf("<warning descr="fmt.Sprintf format %v with arg r causes recursive String method call">%v</warning>", r)
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
