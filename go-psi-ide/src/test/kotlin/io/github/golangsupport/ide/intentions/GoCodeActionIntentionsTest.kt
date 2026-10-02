package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** The type-driven intentions of `ide.intentions` (MIGRATION.md step 9): text before, intention, text after. */
class GoCodeActionIntentionsTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, intention: String, after: String) {
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        // by the exact name: findSingleIntention matches a prefix ("Fill select" / "Fill select with default")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun assertNotOffered(text: String, intention: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertFalse("$intention in $offered", intention in offered)
    }

    // --- fill struct ---

    fun testFillAllFieldsWithEmbeddedStructAndKeptKey() = doTest(
        """
        package p

        type Base struct {
        	ID   int
        	Tags []string
        }

        type User struct {
        	Base
        	Name  string
        	Admin bool
        	Next  *User
        	Home  Address
        }

        type Address struct{ City string }

        func f() User {
        	return User{
        		Name: "x",<caret>
        	}
        }
        """,
        "Fill all fields",
        """
        package p

        type Base struct {
        	ID   int
        	Tags []string
        }

        type User struct {
        	Base
        	Name  string
        	Admin bool
        	Next  *User
        	Home  Address
        }

        type Address struct{ City string }

        func f() User {
        	return User{
        		Name: "x",
        		Base: Base{},
        		Admin: false,
        		Next: nil,
        		Home: Address{},
        	}
        }
        """,
    )

    fun testFillAllFieldsSkipsUnexportedFieldsOfAnotherPackage() = doTest(
        """
        package p

        import "time"

        var t = &time.Timer{<caret>}
        """,
        "Fill all fields",
        """
        package p

        import "time"

        var t = &time.Timer{
        	C: nil,
        }
        """,
    )

    fun testFillAllFieldsOfAnElidedNestedLiteralAddsTheImport() = doTest(
        """
        package p

        import "net/http"

        type Route struct {
        	Path   string
        	Header http.Header
        	Cookie http.Cookie
        }

        var routes = []Route{{<caret>}}
        """,
        "Fill all fields",
        """
        package p

        import "net/http"

        type Route struct {
        	Path   string
        	Header http.Header
        	Cookie http.Cookie
        }

        var routes = []Route{{
        	Path: "",
        	Header: nil,
        	Cookie: http.Cookie{},
        }}
        """,
    )

    fun testFillAllFieldsOfALiteralOnOneLine() = doTest(
        """
        package p

        type T struct{ A, B int }

        var v = T{A: 1<caret>}
        """,
        "Fill all fields",
        """
        package p

        type T struct{ A, B int }

        var v = T{
        	A: 1,
        	B: 0,
        }
        """,
    )

    fun testFillRequiredFields() = doTest(
        """
        package p

        type Config struct {
        	Name    string
        	Port    int
        	Debug   bool
        	Limits  [2]int
        	Logger  interface{ Print(...any) }
        	Plugins []string
        	Env     map[string]string
        	Parent  *Config
        	OnStop  func()
        }

        var c = Config{<caret>}
        """,
        "Fill required fields",
        """
        package p

        type Config struct {
        	Name    string
        	Port    int
        	Debug   bool
        	Limits  [2]int
        	Logger  interface{ Print(...any) }
        	Plugins []string
        	Env     map[string]string
        	Parent  *Config
        	OnStop  func()
        }

        var c = Config{
        	Name: "",
        	Port: 0,
        	Debug: false,
        	Limits: [2]int{},
        }
        """,
    )

    fun testNoFillForAPositionalLiteral() = assertNotOffered(
        """
        package p

        type T struct{ A, B, C int }

        var v = T{1, <caret>2}
        """,
        "Fill all fields",
    )

    // --- fill return values ---

    fun testFillReturnValuesWithErrInScope() = doTest(
        """
        package p

        import "strconv"

        func parse(s string) (int, string, error) {
        	n, err := strconv.Atoi(s)
        	_ = n
        	return<caret>
        }
        """,
        "Fill return values",
        """
        package p

        import "strconv"

        func parse(s string) (int, string, error) {
        	n, err := strconv.Atoi(s)
        	_ = n
        	return n, s, err
        }
        """,
    )

    fun testFillReturnValuesKeepsTheWrittenOnesInTheirPlace() = doTest(
        """
        package p

        type Point struct{ X int }

        func f() (Point, *Point, []int, error) {
        	return <caret>nil
        }
        """,
        "Fill return values",
        """
        package p

        type Point struct{ X int }

        func f() (Point, *Point, []int, error) {
        	return Point{}, nil, nil, nil
        }
        """,
    )

    fun testAddMissingReturn() = doTest(
        """
        package p

        func f(ok bool) (string, error) {
        	if ok {
        		return "", nil
        	}<caret>
        }
        """,
        "Add missing return",
        """
        package p

        func f(ok bool) (string, error) {
        	if ok {
        		return "", nil
        	}
        	return "", nil
        }
        """,
    )

    fun testNoFillForABareReturnWithNamedResults() = assertNotOffered(
        """
        package p

        func f() (n int, err error) {
        	return<caret>
        }
        """,
        "Fill return values",
    )

    // --- fill switch ---

    fun testFillSwitchOverAnIotaEnum() = doTest(
        """
        package p

        type Color int

        const (
        	Red Color = iota
        	Green
        	Blue
        )

        const Other = 7

        func f(c Color) {
        	switch c<caret> {
        	case Green:
        	default:
        	}
        }
        """,
        "Fill switch",
        """
        package p

        type Color int

        const (
        	Red Color = iota
        	Green
        	Blue
        )

        const Other = 7

        func f(c Color) {
        	switch c {
        	case Green:
        	case Red:
        	case Blue:
        	default:
        	}
        }
        """,
    )

    fun testFillTypeSwitchOverAnInterface() = doTest(
        """
        package p

        type Shape interface{ Area() float64 }

        type Circle struct{ R float64 }

        func (c *Circle) Area() float64 { return c.R }

        type Square struct{ S float64 }

        func (s Square) Area() float64 { return s.S }

        func f(s Shape) {
        	switch s.(type) {<caret>}
        }
        """,
        "Fill switch",
        """
        package p

        type Shape interface{ Area() float64 }

        type Circle struct{ R float64 }

        func (c *Circle) Area() float64 { return c.R }

        type Square struct{ S float64 }

        func (s Square) Area() float64 { return s.S }

        func f(s Shape) {
        	switch s.(type) {
        	case *Circle:
        	case Square:
        	}
        }
        """,
    )

    // --- errors ---

    fun testHandleErrorAfterAnAssignment() = doTest(
        """
        package p

        import "os"

        func size(p string) (int, error) {
        	f, err := os.Open(p)<caret>
        	_ = f
        	return 0, nil
        }
        """,
        "Handle error",
        """
        package p

        import "os"

        func size(p string) (int, error) {
        	f, err := os.Open(p)
        	if err != nil {
        		return 0, err
        	}
        	_ = f
        	return 0, nil
        }
        """,
    )

    fun testHandleErrorOfACallStandingAlone() = doTest(
        """
        package p

        import "os"

        func clean(p string) error {
        	os.Remove(p)<caret>
        	return nil
        }
        """,
        "Handle error",
        """
        package p

        import "os"

        func clean(p string) error {
        	if err := os.Remove(p); err != nil {
        		return err
        	}
        	return nil
        }
        """,
    )

    fun testNoHandleErrorWhenTheNextStatementChecksIt() = assertNotOffered(
        """
        package p

        import "os"

        func size(p string) error {
        	_, err := os.Open(p)<caret>
        	if err != nil {
        		return err
        	}
        	return nil
        }
        """,
        "Handle error",
    )

    fun testWrapErrorAddsTheFmtImport() = doTest(
        """
        package p

        import "os"

        type Store struct{}

        func (s *Store) Load(p string) ([]byte, error) {
        	data, err := os.ReadFile(p)
        	if err != nil {
        		return nil, <caret>err
        	}
        	return data, nil
        }
        """,
        "Wrap error with fmt.Errorf",
        """
        package p

        import (
        	"fmt"
        	"os"
        )

        type Store struct{}

        func (s *Store) Load(p string) ([]byte, error) {
        	data, err := os.ReadFile(p)
        	if err != nil {
        		return nil, fmt.Errorf("Store.Load: %w", err)
        	}
        	return data, nil
        }
        """,
    )

    // --- fill select ---

    fun testFillSelectWithContextChannelAndTicker() = doTest(
        """
        package p

        import (
        	"context"
        	"time"
        )

        func run(ctx context.Context, jobs <-chan int) error {
        	t := time.NewTicker(time.Second)
        	select {<caret>
        	}
        }
        """,
        "Fill select",
        """
        package p

        import (
        	"context"
        	"time"
        )

        func run(ctx context.Context, jobs <-chan int) error {
        	t := time.NewTicker(time.Second)
        	select {
        	case <-ctx.Done():
        		return ctx.Err()
        	case v := <-jobs:
        	case <-t.C:
        	}
        }
        """,
    )

    fun testFillSelectKeepsAnExistingCaseOnceAndSendsOnSendOnlyChannels() = doTest(
        """
        package p

        import "context"

        func run(ctx context.Context, out chan<- string, v int) (int, error) {
        	select {
        	case <-ctx.Done():
        		return 0, ctx.Err()
        	default:<caret>
        	}
        }
        """,
        "Fill select",
        """
        package p

        import "context"

        func run(ctx context.Context, out chan<- string, v int) (int, error) {
        	select {
        	case <-ctx.Done():
        		return 0, ctx.Err()
        	case out <- "":
        	default:
        	}
        }
        """,
    )

    fun testFillSelectWithDefaultAndADuration() = doTest(
        """
        package p

        import "time"

        func wait(in chan int, d time.Duration) {
        	v := 0
        	_ = v
        	select {<caret>}
        }
        """,
        "Fill select with default",
        """
        package p

        import "time"

        func wait(in chan int, d time.Duration) {
        	v := 0
        	_ = v
        	select {
        	case v2 := <-in:
        	case <-time.After(d):
        	default:
        	}
        }
        """,
    )

    fun testChannelKey() {
        assertEquals("ctx.Done()", GoFillSelectIntentionBase.channelKey("case <-ctx.Done()"))
        assertEquals("ch", GoFillSelectIntentionBase.channelKey("case v, ok := <- ch"))
        assertEquals("out", GoFillSelectIntentionBase.channelKey("case out <- v"))
        assertNull(GoFillSelectIntentionBase.channelKey("default"))
    }
}
