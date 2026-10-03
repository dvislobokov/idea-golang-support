package io.github.golangsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
 * Grey text from the context ([GoInlineSuggestions]), a test per rule of `docs/INLINE-SUGGESTIONS.md` named by its id, and the negative
 * cases: two equal candidates give nothing. The fixtures are indented with four spaces, and so is a level of the suggestions.
 */
class GoInlineSuggestionsTest : BasePlatformTestCase() {
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

    /** As the provider asks: the idioms first, then the suggestions from the context. */
    private fun shown(source: String): String? {
        val file = myFixture.configureByText("a.go", source.trimIndent())
        val document = myFixture.editor.document
        val caret = myFixture.caretOffset
        return GoIdioms.suggest(document.immutableCharSequence, caret, UNIT, GoIdiomTypes.of(file, document, caret))
            ?: GoInlineSuggestions.suggest(file, document.immutableCharSequence, caret, UNIT)?.text
    }

    private fun assertSuggests(expected: String, source: String, name: String = "a.go") = assertEquals(expected, suggest(source, name))

    private fun assertNothing(source: String, name: String = "a.go") = assertNull(suggest(source, name))

    // --- A: the right side of a declaration ---

    fun testA1MakeSliceWithLen() = assertSuggests("make([]string, len(keys))", """
        package a

        type DumpOption func()

        func WithDumpRedact(keys ...string) DumpOption {
            arr := <caret>
            for i, k := range keys {
                arr[i] = k
            }
            return nil
        }
    """)

    fun testA2MakeSliceWithCapWhenAppended() = assertSuggests("make([]string, 0, len(keys))", """
        package a

        type DumpOption func()

        func WithDumpRedact(keys ...string) DumpOption {
            arr := <caret>
            for _, k := range keys {
                arr = append(arr, k)
            }
            return nil
        }
    """)

    fun testA2MakeSliceWithCapWithNothingBelow() = assertSuggests("make([]string, 0, len(keys))", """
        package a

        type DumpOption func()

        func WithDumpRedact(keys ...string) DumpOption {
            arr := <caret>
        }
    """)

    fun testA2TheTypedPrefixIsCutOff() = assertSuggests("([]string, 0, len(keys))", """
        package a

        func f(keys []string) {
            arr := make<caret>
        }
    """)

    // with two collections A2 does not choose a length; the name leaves the skeleton (A70)
    fun testA2NothingWithTwoCollections() = assertSuggests("make([], 0)", """
        package a

        func f(keys []string, values []string) {
            arr := <caret>
        }
    """)

    fun testA2NothingForAnOrdinaryName() = assertNothing("""
        package a

        func f(keys []string) {
            x := <caret>
        }
    """)

    fun testA5MakeSliceForRange() = assertSuggests("make([]int, 0, len(in))", """
        package a

        func f(in []string, other []int) []int {
            out := <caret>
            for _, s := range in {
                out = append(out, len(s))
            }
            return out
        }
    """)

    fun testA6MakeCopy() = assertSuggests("make([]byte, len(data))", """
        package a

        func f(data []byte) []byte {
            cp := <caret>
            copy(cp, data)
            return cp
        }
    """)

    fun testA7MakeMap() = assertSuggests("make(map[string]int)", """
        package a

        func f(name string, age int) {
            m := <caret>
            m[name] = age
        }
    """)

    fun testA8MakeMapWithLen() = assertSuggests("make(map[string]*User, len(users))", """
        package a

        type User struct{ Name string }

        func f(users []*User) {
            byName := <caret>
            for _, u := range users {
                byName[u.Name] = u
            }
        }
    """)

    fun testA9MakeSet() = assertSuggests("make(map[string]struct{})", """
        package a

        func f(names []string) {
            seen := <caret>
        }
    """)

    fun testA9MakeSetOfBool() = assertSuggests("make(map[string]bool)", """
        package a

        func f(names []string) {
            seen := <caret>
            for _, n := range names {
                seen[n] = true
            }
        }
    """)

    fun testA11MakeCounter() = assertSuggests("make(map[string]int)", """
        package a

        func f(words []string) {
            counts := <caret>
            for _, w := range words {
                counts[w]++
            }
        }
    """)

    fun testA12MakeDoneChannel() = assertSuggests("make(chan struct{})", """
        package a

        func f() {
            done := <caret>
        }
    """)

    fun testA13MakeErrorChannel() = assertSuggests("make(chan error, 1)", """
        package a

        func f() {
            errCh := <caret>
        }
    """)

    fun testA13MakeErrorChannelForTheJobs() = assertSuggests("make(chan error, len(jobs))", """
        package a

        func run(j string) error { return nil }

        func f(jobs []string) {
            errCh := <caret>
            for _, j := range jobs {
                go func() {
                    errCh <- run(j)
                }()
            }
        }
    """)

    fun testA16Context() {
        val suggestion = result("""
            package a

            func f() {
                ctx := <caret>
            }
        """)!!
        assertEquals("context.Background()", suggestion.text)
        assertEquals(setOf("context"), suggestion.imports)
    }

    fun testA16NothingWithAContextInScope() = assertNothing("""
        package a

        import "context"

        func f(parent context.Context) {
            ctx := <caret>
        }
    """)

    fun testA17WithCancel() = assertSuggests("context.WithCancel(ctx)", """
        package a

        import "context"

        func f(ctx context.Context) {
            ctx, cancel := <caret>
        }
    """)

    fun testA18WithTimeout() = assertSuggests("context.WithTimeout(ctx, timeout)", """
        package a

        import (
            "context"
            "time"
        )

        func f(ctx context.Context, timeout time.Duration) {
            ctx, cancel := <caret>
        }
    """)

    fun testA18WithTimeoutByTheNameOfTheFunction() = assertSuggests("context.WithTimeout(ctx, 5*time.Second)", """
        package a

        import "context"

        func callWithTimeout(ctx context.Context) {
            ctx, cancel := <caret>
        }
    """)

    fun testA20Now() = assertSuggests("time.Now()", """
        package a

        func f() {
            start := <caret>
        }
    """)

    fun testA20NothingForAnIndex() = assertNothing("""
        package a

        func f(s []int) {
            start := <caret>
            _ = s[start:]
        }
    """)

    fun testA21Since() = assertSuggests("time.Since(start)", """
        package a

        import "time"

        func f() {
            start := time.Now()
            elapsed := <caret>
        }
    """)

    fun testA25Builder() = assertSuggests(" strings.Builder", """
        package a

        func f() {
            var sb<caret>
        }
    """)

    fun testA26Buffer() = assertSuggests(" bytes.Buffer", """
        package a

        func f() []byte {
            var buf<caret>
            buf.Write(nil)
            return buf.Bytes()
        }
    """)

    fun testA27WaitGroup() = assertSuggests(" sync.WaitGroup", """
        package a

        func f() {
            var wg<caret>
        }
    """)

    fun testA28Mutex() = assertSuggests("sync.Mutex", """
        package a

        func f() {
            var mu <caret>
        }
    """)

    fun testA28RWMutex() = assertSuggests(" sync.RWMutex", """
        package a

        func f() {
            var mu<caret>
            mu.RLock()
        }
    """)

    fun testA30Constructor() = assertSuggests("NewUser(name, email)", """
        package a

        type User struct{ name, email string }

        func NewUser(name string, email string) *User { return &User{name, email} }

        func f(name string, email string) {
            user := <caret>
        }
    """)

    fun testA31Literal() = assertSuggests("&User{}", """
        package a

        type User struct{ Name string }

        func GetUser() (*User, error) {
            u := <caret>
        }
    """)

    fun testA31NothingForAnUnknownName() = assertNothing("""
        package a

        type User struct{ Name string }

        func f() {
            u := <caret>
        }
    """)

    fun testA34ParameterField() = assertSuggests("req.ID", """
        package a

        type GetRequest struct{ ID int }

        func get(req *GetRequest) {
            id := <caret>
        }
    """)

    fun testA39ReadFile() = assertSuggests("os.ReadFile(path)", """
        package a

        func load(path string) {
            data, err := <caret>
        }
    """)

    fun testA41OpenFile() = assertSuggests("os.Open(path)", """
        package a

        func load(path string) {
            f, err := <caret>
        }
    """)

    fun testA41CreateFile() = assertSuggests("os.Create(path)", """
        package a

        func save(path string, data []byte) {
            f, err := <caret>
            f.Write(data)
        }
    """)

    fun testA42ReadBody() = assertSuggests("io.ReadAll(resp.Body)", """
        package a

        import "net/http"

        func read(resp *http.Response) {
            body, err := <caret>
        }
    """)

    fun testA43NewRequest() = assertSuggests("http.NewRequestWithContext(ctx, http.MethodGet, url, nil)", """
        package a

        import "context"

        func get(ctx context.Context, url string) {
            req, err := <caret>
        }
    """)

    fun testA44Do() = assertSuggests("http.DefaultClient.Do(req)", """
        package a

        import "net/http"

        func send(req *http.Request) {
            resp, err := <caret>
        }
    """)

    fun testA44DoWithTheClientOfTheReceiver() = assertSuggests("c.http.Do(req)", """
        package a

        import "net/http"

        type Client struct{ http *http.Client }

        func (c *Client) send(req *http.Request) {
            resp, err := <caret>
        }
    """)

    fun testA51MapLookup() = assertSuggests("users[id]", """
        package a

        func find(users map[int]string, id int) {
            name, ok := <caret>
        }
    """)

    fun testA55TestedCall() = assertSuggests("Sum(tt.a, tt.b)", """
        package a

        import "testing"

        func Sum(a, b int) int { return a + b }

        func TestSum(t *testing.T) {
            tests := []struct {
                name string
                a, b int
                want int
            }{}
            for _, tt := range tests {
                got := <caret>
            }
        }
    """, name = "a_test.go")

    fun testErrWithoutAnObviousCallIsNotGuessed() = assertNothing("""
        package a

        func f() {
            x, err := <caret>
        }
    """)

    // --- B: return ---

    fun testB1ErrorValues() = assertSuggests("0, err", """
        package a

        func g() error { return nil }

        func f() (int, error) {
            err := g()
            if err != nil {
                return <caret>
            }
            return 1, nil
        }
    """)

    fun testB2WrappedError() = assertSuggests("0, fmt.Errorf(\"load: %w\", err)", """
        package a

        import "fmt"

        func load() error { return nil }

        func f() (int, error) {
            if err := load(); err != nil {
                return <caret>
            }
            if err := load(); err != nil {
                return 0, fmt.Errorf("again: %w", err)
            }
            return 1, nil
        }
    """)

    fun testB3BuiltValues() = assertSuggests("u, nil", """
        package a

        type User struct{}

        func f() (*User, error) {
            u := &User{}
            return <caret>
        }
    """)

    fun testB4ConstructorLiteral() = assertSuggests("&Server{addr: addr, port: port}", """
        package a

        type Server struct {
            addr string
            port int
        }

        func NewServer(addr string, port int) *Server {
            return <caret>
        }
    """)

    fun testB11AccumulatedSlice() = assertSuggests("out", """
        package a

        func f(in []int) []string {
            var out []string
            return <caret>
        }
    """)

    fun testB12ContextError() = assertSuggests("0, ctx.Err()", """
        package a

        import "context"

        func f(ctx context.Context, in <-chan int) (int, error) {
            select {
            case <-ctx.Done():
                return <caret>
            case v := <-in:
                return v, nil
            }
        }
    """)

    fun testB3NothingWithTwoCandidates() = assertNothing("""
        package a

        type User struct{}

        func f() (*User, error) {
            a := &User{}
            b := &User{}
            return <caret>
        }
    """)

    // --- C: arguments ---

    fun testC1Context() = assertSuggests("ctx", """
        package a

        import "context"

        func load(ctx context.Context) {}

        func f(ctx context.Context, id int) {
            load(<caret>)
        }
    """)

    fun testC2OnlyVariableOfTheType() = assertSuggests("u", """
        package a

        type User struct{}

        func save(user *User) {}

        func f(u *User, n int) {
            save(<caret>)
        }
    """)

    fun testC3ByTheNameOfTheParameter() = assertSuggests("userID", """
        package a

        type ID int

        func load(id ID) {}

        func f(userID ID, orderNo ID) {
            load(<caret>)
        }
    """)

    fun testC3NothingOnATie() = assertNothing("""
        package a

        type ID int

        func load(id ID) {}

        func f(a ID, b ID) {
            load(<caret>)
        }
    """)

    fun testC4AllArguments() = assertSuggests("ctx, user", """
        package a

        import "context"

        type User struct{}

        func save(ctx context.Context, u *User) error { return nil }

        func f(ctx context.Context, user *User) {
            save(<caret>)
        }
    """)

    fun testC5Spread() = assertSuggests("names...", """
        package a

        func join(parts ...string) string { return "" }

        func f(names []string) {
            join(<caret>)
        }
    """)

    fun testC6Pointer() = assertSuggests("&cfg", """
        package a

        import "encoding/json"

        type Config struct{}

        func f(data []byte) {
            var cfg Config
            json.Unmarshal(data, <caret>)
        }
    """)

    fun testC8FormatArguments() = assertSuggests("name, count", """
        package a

        import "fmt"

        func f(name string, count int) {
            fmt.Printf("%s has %d items", <caret>)
        }
    """)

    fun testC11MakeLength() = assertSuggests("0, len(keys)", """
        package a

        func f(keys []string) {
            arr := make([]string, <caret>)
        }
    """)

    fun testC13TestRun() = assertSuggests("tt.name, func(t *testing.T) {\n            \n        }", """
        package a

        import "testing"

        func TestX(t *testing.T) {
            tests := []struct {
                name string
            }{}
            for _, tt := range tests {
                t.Run(<caret>)
            }
        }
    """, name = "a_test.go")

    // --- D: composite literals ---

    fun testD1AllFields() = assertSuggests("Name: name, Email: email", """
        package a

        type User struct {
            Name  string
            Email string
            Age   int
        }

        func f(name, email string) *User {
            return &User{<caret>}
        }
    """)

    fun testD2SameName() = assertSuggests("name", """
        package a

        type User struct{ Name string }

        func f(name string) User {
            return User{Name: <caret>}
        }
    """)

    fun testD3ParameterField() = assertSuggests("req.Name", """
        package a

        type Request struct{ Name string }
        type User struct{ Name string }

        func f(req *Request) User {
            return User{Name: <caret>}
        }
    """)

    // --- E: range and if ---

    fun testE1PluralCollection() = assertSuggests("users {", """
        package a

        func f(users []string, groups []string) {
            for _, user := range <caret>
        }
    """)

    fun testE2OnlyCollection() = assertSuggests("items {", """
        package a

        func f(items []int) {
            for i, x := range <caret>
        }
    """)

    fun testE2NothingWithTwoCollections() = assertNothing("""
        package a

        func f(a []int, b []int) {
            for i, x := range <caret>
        }
    """)

    fun testE5NotOk() = assertSuggests("!ok {", """
        package a

        func lookup() (string, bool) { return "", false }

        func f() {
            v, ok := lookup()
            if <caret>
        }
    """)

    fun testE8ErrorCheckIsTheIdiom() {
        assertEquals("err != nil {\n        return err\n    }", shown("""
            package a

            func g() (int, error) { return 0, nil }

            func f() error {
                n, err := g()
                if <caret>
            }
        """))
    }

    fun testE12SelectIsTheIdiom() {
        val text = shown("""
            package a

            import "context"

            func f(ctx context.Context) error {
                select {
                <caret>
                }
            }
        """) ?: error("no suggestion")
        assertTrue(text, text.contains("case <-ctx.Done():") && text.contains("return ctx.Err()"))
    }

    // --- F: the next line ---

    fun testF1DeferCancelIsTheIdiom() = assertEquals("defer cancel()", shown("""
        package a

        import "context"

        func f(ctx context.Context) {
            ctx, cancel := context.WithCancel(ctx)
            <caret>
        }
    """))

    fun testF2DeferCloseIsTheIdiom() = assertEquals("defer f.Close()", shown("""
        package a

        import "os"

        func g(path string) error {
            f, err := os.Open(path)
            if err != nil {
                return err
            }
            <caret>
        }
    """))

    fun testF3DeferBodyCloseIsTheIdiom() = assertEquals("defer resp.Body.Close()", shown("""
        package a

        import "net/http"

        func g(url string) error {
            resp, err := http.Get(url)
            if err != nil {
                return err
            }
            <caret>
        }
    """))

    fun testF4DeferRowsCloseIsTheIdiom() {
        val text = "func g() error {\n\trows, err := db.Query(q)\n\tif err != nil {\n\t\treturn err\n\t}\n\t"
        assertEquals("defer rows.Close()", GoIdioms.suggest(text, text.length, "\t"))
    }

    fun testF5DeferUnlockIsTheIdiom() = assertEquals("defer mu.Unlock()", GoIdioms.suggest("func g() {\n\tmu.Lock()\n\t", "func g() {\n\tmu.Lock()\n\t".length, "\t"))

    fun testF6GoroutineAfterAdd() = assertSuggests("go func() {\n        defer wg.Done()\n        \n    }()", """
        package a

        import "sync"

        func f() {
            var wg sync.WaitGroup
            wg.Add(1)
            <caret>
        }
    """)

    fun testF7DoneInGoroutine() = assertSuggests("defer wg.Done()", """
        package a

        import "sync"

        func f(wg *sync.WaitGroup) {
            go func() {
                <caret>
            }()
        }
    """)

    fun testF8Wait() = assertSuggests("wg.Wait()", """
        package a

        import "sync"

        func f(jobs []int) {
            var wg sync.WaitGroup
            for range jobs {
                wg.Add(1)
                go func() {
                    defer wg.Done()
                }()
            }
            <caret>
        }
    """)

    fun testF9DeferStopIsTheIdiom() = assertEquals("defer ticker.Stop()", GoIdioms.suggest("func g() {\n\tticker := time.NewTicker(time.Second)\n\t", "func g() {\n\tticker := time.NewTicker(time.Second)\n\t".length, "\t"))

    fun testF12ServerClose() = assertSuggests("defer srv.Close()", """
        package a

        import (
            "net/http"
            "net/http/httptest"
            "testing"
        )

        func TestX(t *testing.T) {
            srv := httptest.NewServer(http.NotFoundHandler())
            <caret>
        }
    """, name = "a_test.go")

    fun testF13Helper() = assertSuggests("t.Helper()", """
        package a

        import "testing"

        func assertOK(t *testing.T, err error) {
            <caret>
        }
    """, name = "a_test.go")

    fun testF13NothingInATest() = assertNothing("""
        package a

        import "testing"

        func TestOK(t *testing.T) {
            <caret>
        }
    """, name = "a_test.go")

    fun testF15DeferRollbackIsTheIdiom() {
        val text = "func g() error {\n\ttx, err := db.BeginTx(ctx, nil)\n\tif err != nil {\n\t\treturn err\n\t}\n\t"
        assertEquals("defer tx.Rollback()", GoIdioms.suggest(text, text.length, "\t"))
    }

    fun testF20DeferStopOfNotifyContextIsTheIdiom() {
        val text = "func g() {\n\tctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)\n\t"
        assertEquals("defer stop()", GoIdioms.suggest(text, text.length, "\t"))
    }

    // --- G: bodies ---

    fun testG1Constructor() = assertSuggests("return &Server{addr: addr}", """
        package a

        type Server struct{ addr string }

        func NewServer(addr string) *Server {
            <caret>
        }
    """)

    fun testG2Getter() = assertSuggests("return u.name", """
        package a

        type User struct{ name string }

        func (u *User) Name() string {
            <caret>
        }
    """)

    fun testG3Setter() = assertSuggests("u.name = name", """
        package a

        type User struct{ name string }

        func (u *User) SetName(name string) {
            <caret>
        }
    """)

    fun testG6Unwrap() = assertSuggests("return e.err", """
        package a

        type QueryError struct {
            query string
            err   error
        }

        func (e *QueryError) Unwrap() error {
            <caret>
        }
    """)

    // --- H ---

    fun testH2ErrorVariable() {
        val suggestion = result("""
            package a

            var ErrNotFound = <caret>
        """)!!
        assertEquals("errors.New(\"not found\")", suggestion.text)
        assertEquals(setOf("errors"), suggestion.imports)
    }

    // --- the mechanics ---

    fun testTheImportIsAddedOnAccept() {
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n    ctx := <caret>\n}\n")
        val document = myFixture.editor.document
        val suggestion = GoInlineSuggestions.suggest(myFixture.file, document.immutableCharSequence, myFixture.caretOffset, UNIT)!!
        WriteCommandAction.runWriteCommandAction(project) { GoInlineSuggestions.accept(document, myFixture.caretOffset, suggestion) }
        assertEquals("package a\n\nimport \"context\"\n\nfunc f() {\n    ctx := context.Background()\n}\n", document.text)
    }

    fun testTheImportedNameIsUsed() = assertSuggests("ctxpkg.Background()", """
        package a

        import ctxpkg "context"

        var _ ctxpkg.Context

        func f() {
            ctx := <caret>
        }
    """)

    fun testUncommittedTextIsRead() {
        myFixture.configureByText("a.go", "package a\n\nfunc f(keys []string) {\n    <caret>\n}\n")
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(myFixture.caretOffset, "arr := ") }
        val document = myFixture.editor.document
        val offset = document.text.indexOf("arr := ") + "arr := ".length
        assertEquals("make([]string, 0, len(keys))", GoInlineSuggestions.suggest(myFixture.file, document.immutableCharSequence, offset, UNIT)?.text)
    }

    fun testNothingInAString() = assertNothing("""
        package a

        func f(keys []string) {
            s := "arr := <caret>"
        }
    """)

    fun testSlots() {
        assertEquals(GoInlineSuggestions.Kind.DECLARATION, GoInlineSuggestions.slotAt("\tx := ", 6)?.kind)
        assertEquals(GoInlineSuggestions.Kind.RETURN, GoInlineSuggestions.slotAt("\treturn ", 8)?.kind)
        assertEquals(GoInlineSuggestions.Kind.ARGUMENT, GoInlineSuggestions.slotAt("\tf(a, )", 6)?.kind)
        assertEquals("Name", GoInlineSuggestions.slotAt("\tT{Name: }", 9)?.field)
        assertEquals(GoInlineSuggestions.Kind.RANGE, GoInlineSuggestions.slotAt("\tfor _, x := range ", 19)?.kind)
        assertEquals(GoInlineSuggestions.Kind.VAR_TYPE, GoInlineSuggestions.slotAt("\tvar wg", 7)?.kind)
        assertNull(GoInlineSuggestions.slotAt("\tx := y", 5))
        assertNull(GoInlineSuggestions.slotAt("\tf(\"(\", ) // (", 15))
    }

    private companion object {
        const val UNIT = "    "
    }
}
