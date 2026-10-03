package io.github.golangsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.lang.GoIdiomTypes
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoInlineSuggestions
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Paths

/**
 * The second batch (P2) of `docs/INLINE-SUGGESTIONS.md`: a test per rule named by its id, and a negative case for each where a
 * neighbour of the rule is not sure. Same fixtures as [GoInlineSuggestionsTest]: four spaces a level.
 */
class GoInlineSuggestionsP2Test : BasePlatformTestCase() {
    private var languageServer = true

    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
        val goroot = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")
        val toolchain = GoToolchainInfo(
            goroot = goroot, version = DefaultGoToolchainProvider.readVersion(goroot), gopath = emptyList(),
            gomodcache = Paths.get(System.getProperty("user.home"), "go", "pkg", "mod"), goos = "linux", goarch = "amd64", cgoEnabled = true,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        VfsRootAccess.allowRootAccess(testRootDisposable, goroot.toString())
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    private fun result(source: String, name: String = "a.go"): GoInlineSuggestions.Suggestion? {
        val file = myFixture.configureByText(name, source.trimIndent())
        return GoInlineSuggestions.suggest(file, myFixture.editor.document.immutableCharSequence, myFixture.caretOffset, UNIT)
    }

    private fun suggest(source: String, name: String = "a.go"): String? = result(source, name)?.text

    private fun shown(source: String): String? {
        val file = myFixture.configureByText("a.go", source.trimIndent())
        val document = myFixture.editor.document
        val caret = myFixture.caretOffset
        return GoIdioms.suggest(document.immutableCharSequence, caret, UNIT, GoIdiomTypes.of(file, document, caret))
            ?: GoInlineSuggestions.suggest(file, document.immutableCharSequence, caret, UNIT)?.text
    }

    private fun assertSuggests(expected: String, source: String, name: String = "a.go") = assertEquals(expected, suggest(source, name))

    private fun assertNothing(source: String, name: String = "a.go") = assertNull(suggest(source, name))

    // --- A: slices, maps, channels ---

    fun testA3MakeSliceOfAppended() = assertSuggests("make([]*User, 0)", """
        package a

        type User struct{ Name string }

        func load() *User { return nil }

        func f() []*User {
            arr := <caret>
            arr = append(arr, load())
            return arr
        }
    """)

    fun testA3NothingWhenTwoTypesAreAppended() = assertNothing("""
        package a

        func f() {
            arr := <caret>
            arr = append(arr, 1)
            arr = append(arr, "x")
        }
    """)

    fun testA4SliceType() = assertSuggests(" []*User", """
        package a

        type User struct{ Name string }

        func load() *User { return nil }

        func f() []*User {
            var arr<caret>
            arr = append(arr, load())
            return arr
        }
    """)

    fun testA4NothingWithoutAppend() = assertNothing("""
        package a

        func f() {
            var arr<caret>
            _ = arr
        }
    """)

    fun testA10MapByField() = assertSuggests("make(map[int]*User, len(users))", """
        package a

        type User struct{ ID int }

        func f(users []*User) {
            byID := <caret>
        }
    """)

    fun testA10NothingWithTwoSlices() = assertNothing("""
        package a

        type User struct{ ID int }

        func f(users []*User, admins []*User) {
            byID := <caret>
        }
    """)

    fun testA14ChannelForJobs() = assertSuggests("make(chan int, len(jobs))", """
        package a

        func work(s string) int { return len(s) }

        func f(jobs []string) {
            results := <caret>
            for _, j := range jobs {
                go func() {
                    results <- work(j)
                }()
            }
        }
    """)

    fun testA15MakeChannel() = assertSuggests("make(chan string)", """
        package a

        func f(name string) {
            ch := <caret>
            go func() {
                ch <- name
            }()
            <-ch
        }
    """)

    fun testA15NothingWithoutASend() = assertNothing("""
        package a

        func f() {
            ch := <caret>
            <-ch
        }
    """)

    fun testA35Length() = assertSuggests("len(items)", """
        package a

        func f(items []string) {
            n := <caret>
        }
    """)

    fun testA35NothingWithTwoCollections() = assertNothing("""
        package a

        func f(items []string, other map[string]int) {
            n := <caret>
        }
    """)

    fun testA36Zero() = assertSuggests("0", """
        package a

        func f(items []string) int {
            count := <caret>
            for range items {
                count++
            }
            return count
        }
    """)

    fun testA36ZeroOfAFloat() = assertSuggests("0.0", """
        package a

        func f(prices []float64) float64 {
            total := <caret>
            for _, p := range prices {
                total += p
            }
            return total
        }
    """)

    fun testA36NothingForADuration() = assertNothing("""
        package a

        import "time"

        func f(ds []time.Duration) time.Duration {
            total := <caret>
            for _, d := range ds {
                total += d
            }
            return total
        }
    """)

    fun testA52Receive() = assertSuggests("<-ch", """
        package a

        func f(ch chan int) {
            v, ok := <caret>
        }
    """)

    fun testA52NothingWithAMap() = assertSuggests("m[k]", """
        package a

        func f(ch chan int, m map[string]int, k string) {
            v, ok := <caret>
        }
    """)

    fun testA65KeysBeforeGo123() = assertSuggests("make([]string, 0, len(m))", """
        package a

        func f(m map[string]int) {
            keys := <caret>
        }
    """)

    fun testA65NothingWithTwoMaps() = assertNothing("""
        package a

        func f(m map[string]int, other map[string]bool) {
            keys := <caret>
        }
    """)

    // --- A: the library ---

    fun testA22Deadline() = assertSuggests("time.Now().Add(timeout)", """
        package a

        import "time"

        func f(timeout time.Duration) {
            deadline := <caret>
        }
    """)

    fun testA22NothingWithoutADuration() = assertNothing("""
        package a

        func f() {
            deadline := <caret>
        }
    """)

    fun testA23Ticker() = assertSuggests("time.NewTicker(interval)", """
        package a

        import "time"

        func f(interval time.Duration) {
            ticker := <caret>
        }
    """)

    fun testA24Timer() = assertSuggests("time.NewTimer(delay)", """
        package a

        import "time"

        func f(delay, interval time.Duration) {
            timer := <caret>
        }
    """)

    fun testA24NothingWithTwoUnnamedDurations() = assertNothing("""
        package a

        import "time"

        func f(a, b time.Duration) {
            timer := <caret>
        }
    """)

    fun testA29Once() = assertSuggests(" sync.Once", """
        package a

        func f() {
            var once<caret>
        }
    """)

    fun testA32DefaultConfig() = assertSuggests("DefaultConfig()", """
        package a

        type Config struct{ Port int }

        func DefaultConfig() Config { return Config{} }

        func f() {
            cfg := <caret>
        }
    """)

    fun testA32NothingWithTwoFactories() = assertNothing("""
        package a

        type Config struct{ Port int }

        func DefaultConfig() Config { return Config{} }
        func NewConfig() *Config { return nil }

        func f() {
            cfg := <caret>
        }
    """)

    fun testA33ReceiverField() = assertSuggests("s.name", """
        package a

        type Server struct{ name string }

        func (s *Server) Start() {
            name := <caret>
        }
    """)

    fun testA38Atoi() = assertSuggests("strconv.Atoi(idStr)", """
        package a

        func f(idStr string) {
            id, err := <caret>
        }
    """)

    fun testA38ParseIntForAnInt64() = assertSuggests("strconv.ParseInt(idStr, 10, 64)", """
        package a

        func get(id int64) {}

        func f(idStr string) {
            id, err := <caret>
            get(id)
        }
    """)

    fun testA38NothingForAnOrdinaryString() = assertNothing("""
        package a

        func f(name string) {
            id, err := <caret>
        }
    """)

    fun testA40Marshal() = assertSuggests("json.Marshal(u)", """
        package a

        import "net/http"

        type User struct{ Name string }

        func f(w http.ResponseWriter, u *User) {
            data, err := <caret>
            w.Write(data)
        }
    """)

    fun testA40NothingWithoutAWrite() = assertNothing("""
        package a

        type User struct{ Name string }

        func f(u *User) {
            data, err := <caret>
        }
    """)

    fun testA45Query() = assertSuggests("db.QueryContext(ctx, query)", """
        package a

        import (
            "context"
            "database/sql"
        )

        func f(ctx context.Context, db *sql.DB, query string) {
            rows, err := <caret>
        }
    """)

    fun testA45QueryOfTheReceiver() = assertSuggests("s.db.Query(q)", """
        package a

        import "database/sql"

        type Store struct{ db *sql.DB }

        func (s *Store) f(q string) {
            rows, err := <caret>
        }
    """)

    fun testA45NothingWithoutAQuery() = assertNothing("""
        package a

        import "database/sql"

        func f(db *sql.DB) {
            rows, err := <caret>
        }
    """)

    fun testA46Decoder() = assertSuggests("json.NewDecoder(r.Body)", """
        package a

        import "net/http"

        func f(w http.ResponseWriter, r *http.Request) {
            dec := <caret>
        }
    """)

    fun testA47Encoder() = assertSuggests("json.NewEncoder(w)", """
        package a

        import "net/http"

        func f(w http.ResponseWriter, r *http.Request) {
            enc := <caret>
        }
    """)

    fun testA47NothingWithoutAWriter() = assertNothing("""
        package a

        func f() {
            enc := <caret>
        }
    """)

    fun testA48RegexpPutsTheCaretInsideTheQuotes() {
        val suggestion = result("""
            package a

            var emailRe = <caret>
        """)!!
        assertEquals("regexp.MustCompile(``)", suggestion.text)
        assertEquals(2, suggestion.caretBack)
        assertEquals(setOf("regexp"), suggestion.imports)
    }

    fun testA48NothingForAnOrdinaryName() = assertNothing("""
        package a

        var email = <caret>
    """)

    // --- A: tests ---

    fun testA56Want() = assertSuggests("tt.want", """
        package a

        import "testing"

        func TestF(t *testing.T) {
            tests := []struct {
                name string
                want int
            }{}
            for _, tt := range tests {
                want := <caret>
            }
        }
    """, "a_test.go")

    fun testA56NothingOutsideTheLoop() = assertNothing("""
        package a

        import "testing"

        func TestF(t *testing.T) {
            want := <caret>
        }
    """, "a_test.go")

    fun testA57Table() {
        myFixture.addFileToProject("sum.go", "package a\n\nfunc Sum(a, b int) (int, error) { return a + b, nil }\n")
        assertSuggests("[]struct {\n        name    string\n        a       int\n        b       int\n        want    int\n        wantErr bool\n    }{\n        {name: \"\"},\n    }", """
            package a

            import "testing"

            func TestSum(t *testing.T) {
                tests := <caret>
            }
        """, "sum_test.go")
    }

    fun testA57NothingForAVariadicFunction() {
        myFixture.addFileToProject("sum.go", "package a\n\nfunc Sum(xs ...int) int { return 0 }\n")
        assertNothing("""
            package a

            import "testing"

            func TestSum(t *testing.T) {
                tests := <caret>
            }
        """, "sum_test.go")
    }

    fun testA58Server() = assertSuggests("httptest.NewServer(handler)", """
        package a

        import (
            "net/http"
            "testing"
        )

        func TestF(t *testing.T) {
            handler := http.NewServeMux()
            srv := <caret>
        }
    """, "a_test.go")

    fun testA59Recorder() = assertSuggests("httptest.NewRecorder()", """
        package a

        import "testing"

        func TestF(t *testing.T) {
            rec := <caret>
        }
    """, "a_test.go")

    fun testA59NothingOutsideATest() = assertNothing("""
        package a

        func f() {
            rec := <caret>
        }
    """)

    fun testA60Request() = assertSuggests("httptest.NewRequest(http.MethodGet, \"/\", nil)", """
        package a

        import "testing"

        func TestF(t *testing.T) {
            req := <caret>
        }
    """, "a_test.go")

    fun testA61TempDir() = assertSuggests("t.TempDir()", """
        package a

        import "testing"

        func TestF(t *testing.T) {
            dir := <caret>
        }
    """, "a_test.go")

    // --- B: return ---

    fun testB6ErrorMessage() = assertSuggests("fmt.Sprintf(\"%s: %v\", e.msg, e.err)", """
        package a

        type QueryError struct {
            msg string
            err error
        }

        func (e *QueryError) Error() string {
            return <caret>
        }
    """)

    fun testB7OppositeBool() = assertSuggests("false", """
        package a

        func valid(s string) bool {
            if s == "" {
                return <caret>
            }
            return true
        }
    """)

    fun testB7OppositeAtTheEnd() = assertSuggests("true", """
        package a

        func valid(s string) bool {
            if s == "" {
                return false
            }
            return <caret>
        }
    """)

    fun testB7NothingWhenTheEarlyReturnsDiffer() = assertNothing("""
        package a

        func valid(s string) bool {
            if s == "" {
                return false
            }
            if s == "x" {
                return true
            }
            return <caret>
        }
    """)

    fun testB9Len() = assertSuggests("len(s)", """
        package a

        type byName []string

        func (s byName) Len() int {
            return <caret>
        }
    """)

    fun testB13JoinedErrors() = assertSuggests("errors.Join(errs...)", """
        package a

        func f() error {
            var errs []error
            return <caret>
        }
    """)

    // --- C: arguments ---

    fun testC9ErrorsIs() = assertSuggests("ErrNotFound", """
        package a

        import "errors"

        var ErrNotFound = errors.New("not found")

        func f(err error) bool {
            return errors.Is(err, <caret>)
        }
    """)

    fun testC9NothingWithTwoSentinels() = assertNothing("""
        package a

        import "errors"

        var ErrNotFound = errors.New("not found")
        var ErrClosed = errors.New("closed")

        func f(err error) bool {
            return errors.Is(err, <caret>)
        }
    """)

    fun testC12AppendedElement() = assertSuggests("u", """
        package a

        type User struct{ Name string }

        func f(users []*User, other *User) []*User {
            var out []*User
            for _, u := range users {
                out = append(out, <caret>)
            }
            return out
        }
    """)

    fun testC14AssertEqual() = assertSuggests("tt.want, got", """
        package a

        import "testing"

        type assertions struct{}

        func (assertions) Equal(t *testing.T, expected, actual any) {}

        var assert assertions

        func TestF(t *testing.T) {
            tests := []struct {
                name string
                want int
            }{}
            for _, tt := range tests {
                got := 1
                assert.Equal(t, <caret>)
            }
        }
    """, "a_test.go")

    fun testC17NotifyContext() = assertSuggests("ctx, os.Interrupt, syscall.SIGTERM", """
        package a

        import (
            "context"
            "os/signal"
        )

        func f(ctx context.Context) {
            ctx, stop := signal.NotifyContext(<caret>)
        }
    """)

    fun testC18SortSlice() = assertSuggests("func(i, j int) bool { return users[i].Name < users[j].Name }", """
        package a

        import "sort"

        type User struct {
            Name string
            Age  int
        }

        func f(users []User) {
            sort.Slice(users, <caret>)
        }
    """)

    fun testC18NothingWithoutAKey() = assertNothing("""
        package a

        import "sort"

        type User struct{ Age int }

        func f(users []User) {
            sort.Slice(users, <caret>)
        }
    """)

    fun testC19SortFunc() = assertSuggests("func(a, b *User) int { return cmp.Compare(a.ID, b.ID) }", """
        package a

        import "slices"

        type User struct{ ID int }

        func f(users []*User) {
            slices.SortFunc(users, <caret>)
        }
    """)

    // --- D: literals ---

    fun testD4Now() = assertSuggests("time.Now()", """
        package a

        import "time"

        type Event struct{ CreatedAt time.Time }

        func f() {
            _ = Event{CreatedAt: <caret>}
        }
    """)

    fun testD4NothingForAnExpiry() = assertNothing("""
        package a

        import "time"

        type Event struct{ ExpiresAt time.Time }

        func f() {
            _ = Event{ExpiresAt: <caret>}
        }
    """)

    fun testD5Service() = assertSuggests("log", """
        package a

        import "log/slog"

        type Server struct{ Logger *slog.Logger }

        func f(log *slog.Logger) {
            _ = Server{Logger: <caret>}
        }
    """)

    fun testD7Server() = assertSuggests("Addr: addr, Handler: mux", """
        package a

        import "net/http"

        func f(addr string) {
            mux := http.NewServeMux()
            _ = &http.Server{<caret>}
        }
    """)

    // --- E: control flow ---

    fun testE3RangeChannel() = assertSuggests("range ch {", """
        package a

        func produce(ch chan<- int) {}

        func f() {
            ch := make(chan int)
            go produce(ch)
            for <caret>
        }
    """)

    fun testE3NothingWithoutAChannelAbove() = assertNothing("""
        package a

        func f(ch chan int) {
            x := 1
            for <caret>
        }
    """)

    fun testE4CountedLoop() = assertSuggests("0; i < len(items); i++ {", """
        package a

        func f(items []string) {
            for i := <caret>
        }
    """)

    fun testE6NilParameter() = assertSuggests("u == nil {", """
        package a

        type User struct{ Name string }

        func f(u *User) string {
            if <caret>
            return u.Name
        }
    """)

    fun testE7EmptyParameter() = assertSuggests("len(items) == 0 {", """
        package a

        func f(items []string) string {
            if <caret>
            return items[0]
        }
    """)

    fun testE6E7NothingWithBoth() = assertNothing("""
        package a

        type User struct{ Name string }

        func f(u *User, items []string) string {
            if <caret>
            return u.Name + items[0]
        }
    """)

    fun testE9ErrorsIs() = assertSuggests("Is(err, ErrNotFound) {", """
        package a

        import "errors"

        var ErrNotFound = errors.New("not found")

        func get() error { return nil }

        func f() {
            err := get()
            if errors.<caret>
        }
    """)

    fun testE10Enum() = assertSuggests("c {", """
        package a

        type Color int

        const (
            Red Color = iota
            Green
        )

        func f(c Color, n int) {
            switch <caret>
        }
    """)

    fun testE11TypeSwitch() = assertSuggests("v.(type) {", """
        package a

        func f(v any, n int) {
            switch x := <caret>
        }
    """)

    fun testE11NothingWithTwoInterfaces() = assertNothing("""
        package a

        func f(v any, w error) {
            switch x := <caret>
        }
    """)

    // --- F: the next line ---

    fun testF10DeferTimerStopIsTheIdiom() = assertEquals("defer timer.Stop()", GoIdioms.suggest("func g() {\n\ttimer := time.NewTimer(d)\n\t", "func g() {\n\ttimer := time.NewTimer(d)\n\t".length, "\t"))

    fun testF16DeferListenerCloseIsTheIdiom() {
        val text = "func g() error {\n\tl, err := net.Listen(\"tcp\", addr)\n\tif err != nil {\n\t\treturn err\n\t}\n\t"
        assertEquals("defer l.Close()", GoIdioms.suggest(text, text.length, "\t"))
    }

    fun testF17DeferConnCloseIsTheIdiom() {
        val text = "func g() error {\n\tconn, err := net.Dial(\"tcp\", addr)\n\tif err != nil {\n\t\treturn err\n\t}\n\t"
        assertEquals("defer conn.Close()", GoIdioms.suggest(text, text.length, "\t"))
    }

    fun testF19DeferStmtCloseIsTheIdiom() {
        val text = "func g() error {\n\tstmt, err := db.Prepare(q)\n\tif err != nil {\n\t\treturn err\n\t}\n\t"
        assertEquals("defer stmt.Close()", GoIdioms.suggest(text, text.length, "\t"))
    }

    fun testF11CloseAfterProducer() = assertSuggests("close(ch)", """
        package a

        func f(items []int) <-chan int {
            ch := make(chan int)
            go func() {
                for _, x := range items {
                    ch <- x
                }
                <caret>
            }()
            return ch
        }
    """)

    fun testF11NothingWhenClosedByDefer() = assertNothing("""
        package a

        func f(items []int) <-chan int {
            ch := make(chan int)
            go func() {
                defer close(ch)
                for _, x := range items {
                    ch <- x
                }
                <caret>
            }()
            return ch
        }
    """)

    fun testF14Parallel() = assertSuggests("t.Parallel()", """
        package a

        import "testing"

        func TestA(t *testing.T) {
            t.Parallel()
        }

        func TestB(t *testing.T) {
            <caret>
            _ = 1
        }
    """, "a_test.go")

    fun testF14NothingWhenAnotherTestIsNotParallel() = assertNothing("""
        package a

        import "testing"

        func TestA(t *testing.T) {
            t.Parallel()
        }

        func TestC(t *testing.T) {
            _ = 2
        }

        func TestB(t *testing.T) {
            <caret>
            _ = 1
        }
    """, "a_test.go")

    fun testF18BuilderString() = assertSuggests("return sb.String()", """
        package a

        import "strings"

        func join(parts []string) string {
            var sb strings.Builder
            for _, p := range parts {
                sb.WriteString(p)
            }
            <caret>
        }
    """)

    // --- G: bodies ---

    fun testG4EnumString() = assertSuggests(
        "switch c {\n    case Red:\n        return \"Red\"\n    case Green:\n        return \"Green\"\n    }\n    return fmt.Sprintf(\"Color(%d)\", int(c))", """
        package a

        type Color int

        const (
            Red Color = iota
            Green
        )

        func (c Color) String() string {
            <caret>
        }
    """)

    fun testG4NothingWithAnAlias() = assertNothing("""
        package a

        type Color int

        const (
            Red   Color = 0
            Green Color = 1
            Default = Red
        )

        func (c Color) String() string {
            <caret>
        }
    """)

    fun testG5ErrorBody() = assertSuggests("return e.msg", """
        package a

        type ParseError struct{ msg string }

        func (e *ParseError) Error() string {
            <caret>
        }
    """)

    fun testG8TestBody() {
        myFixture.addFileToProject("sum.go", "package a\n\nfunc Sum(a, b int) int { return a + b }\n")
        assertSuggests(
            "tests := []struct {\n        name string\n        a    int\n        b    int\n        want int\n    }{\n        {name: \"\"},\n    }\n" +
                "    for _, tt := range tests {\n        t.Run(tt.name, func(t *testing.T) {\n            got := Sum(tt.a, tt.b)\n" +
                "            if got != tt.want {\n                t.Errorf(\"Sum() = %v, want %v\", got, tt.want)\n            }\n        })\n    }", """
            package a

            import "testing"

            func TestSum(t *testing.T) {
                <caret>
            }
        """, "sum_test.go")
    }

    fun testG9BenchmarkBody() {
        myFixture.addFileToProject("run.go", "package a\n\nfunc Run() {}\n")
        assertSuggests("for i := 0; i < b.N; i++ {\n        Run()\n    }", """
            package a

            import "testing"

            func BenchmarkRun(b *testing.B) {
                <caret>
            }
        """, "run_test.go")
    }

    // --- H: package level ---

    fun testH1InterfaceAssertion() = assertSuggests("var _ Store = (*memory)(nil)", """
        package a

        type Store interface{ Get(key string) string }

        func (m *memory) Get(key string) string { return "" }

        type memory struct{}
        <caret>
    """)

    fun testH1NothingWithTwoInterfaces() = assertNothing("""
        package a

        type Store interface{ Get(key string) string }
        type Getter interface{ Get(key string) string }

        func (m *memory) Get(key string) string { return "" }

        type memory struct{}
        <caret>
    """)

    fun testH4RegexpAtPackageLevel() = assertEquals("regexp.MustCompile(``)", suggest("""
        package a

        var re = <caret>
    """))

    fun testIdiomsStillComeFirst() = assertEquals("defer ticker.Stop()", shown("""
        package a

        import "time"

        func f(interval time.Duration) {
            ticker := time.NewTicker(interval)
            <caret>
        }
    """))

    private companion object {
        const val UNIT = "    "
    }
}
