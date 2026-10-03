package io.github.golangsupport.ide.rules.staticcheck

import com.intellij.openapi.util.Disposer
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules

/**
 * Batch B2 (staticcheck SA1xxx call contracts, govet `unmarshal` / `sigchanyzer`). Fixtures mark the expected problem of a line with a
 * trailing `// want [ID] message`; every other line must stay quiet (only B2 ids are compared). Each fixture has an aliased import
 * and a non-constant argument; quick fixes are checked text before / after.
 */
class GoStaticcheckCallRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private val settings: GoRuleSettings get() = GoRuleSettings.getInstance(project)

    private fun check(text: String) {
        val source = text.trimIndent() + "\n"
        val expected = source.lines().mapIndexedNotNull { i, line ->
            line.substringAfter("// want ", "").takeIf { it.isNotEmpty() }?.let { "${i + 1}: $it" }
        }
        myFixture.configureByText("sc.go", source)
        val document = myFixture.editor.document
        val actual = myFixture.doHighlighting().filter { B2.containsMatchIn(it.description ?: "") }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    private fun fix(before: String, fix: String, after: String) {
        myFixture.configureByText("fix.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    fun testAllRegistered() {
        val rules = GoRuleSet.getInstance(project).allRules.associateBy { it.id }
        for (id in IDS) {
            val rule = rules[id] ?: error("$id is not registered")
            val linter = if (id.startsWith("govet:")) "govet" else "staticcheck"
            assertEquals(id, linter, rule.linter)
            assertTrue(id, rule.enabledByDefault)
            assertEquals(id, GoRuleLevel.WARNING, rule.defaultLevel)
            assertTrue(id, rule.description.isNotBlank() && rule.title.isNotBlank())
        }
    }

    fun testSA1000InvalidRegexp() = check("""
        package sc

        import (
        	"regexp"
        	re "regexp"
        )

        func MustCompile(s string) {}

        func f(dynamic string) {
        	regexp.MustCompile("[") // want [SA1000] error parsing regexp: missing closing ]: `[`
        	_, _ = re.Compile("a**") // want [SA1000] error parsing regexp: invalid nested repetition operator: `**`
        	_, _ = regexp.MatchString(`(?P<n!>x)`, "s") // want [SA1000] error parsing regexp: invalid named capture: `(?P<n!>`
        	const p = "(" + "x"
        	regexp.MustCompile(p) // want [SA1000] error parsing regexp: missing closing ): `(x`
        	regexp.MustCompile(`^[a-z]+\d*${'$'}`)
        	regexp.MustCompile(dynamic)
        	MustCompile("[")
        }
    """)

    fun testSA1000DotImport() = check("""
        package sc

        import . "regexp"

        func f() {
        	MustCompile("a)") // want [SA1000] error parsing regexp: unexpected ): `a)`
        }
    """)

    fun testSA1004Sleep() = check("""
        package sc

        import (
        	"time"
        	t2 "time"
        )

        func f(n time.Duration) {
        	time.Sleep(5) // want [SA1004] sleeping for 5 nanoseconds is probably a bug; be explicit if it isn't: time.Sleep(5 * time.Nanosecond)
        	t2.Sleep(1) // want [SA1004] sleeping for 1 nanoseconds is probably a bug; be explicit if it isn't: time.Sleep(time.Nanosecond)
        	time.Sleep(0)
        	time.Sleep(121)
        	time.Sleep(n)
        	time.Sleep(5 * time.Second)
        	time.Sleep(0x5)
        }
    """)

    fun testSA1005ExecCommand() = check("""
        package sc

        import (
        	"os/exec"
        	x "os/exec"
        )

        func f(name string) {
        	_ = exec.Command("git status") // want [SA1005] first argument to exec.Command looks like a shell command, but a program name or path are expected
        	_ = x.Command("ls -l", "x") // want [SA1005] first argument to exec.Command looks like a shell command, but a program name or path are expected
        	_ = exec.Command("git", "status")
        	_ = exec.Command("/usr/bin/my tool")
        	_ = exec.Command(name)
        }
    """)

    fun testSA1006DynamicFormat() = check("""
        package sc

        import (
        	"fmt"
        	f2 "fmt"
        	"log"
        	"os"
        )

        func msg() string { return "" }

        func f(s string, err error) {
        	fmt.Printf(s) // want [SA1006] printf-style function with dynamic format string and no further arguments should use print-style function instead
        	_ = f2.Sprintf(msg()) // want [SA1006] printf-style function with dynamic format string and no further arguments should use print-style function instead
        	log.Printf(s) // want [SA1006] printf-style function with dynamic format string and no further arguments should use print-style function instead
        	fmt.Fprintf(os.Stdout, err.Error()) // want [SA1006] printf-style function with dynamic format string and no further arguments should use print-style function instead
        	fmt.Printf(s, 1)
        	fmt.Printf("%d", 1)
        	const c = "x"
        	fmt.Printf(c)
        	fmt.Printf(os.Args[0])
        }
    """)

    fun testSA1007Url() = check("""
        package sc

        import (
        	"net/url"
        	u "net/url"
        )

        func f(s string) {
        	_, _ = url.Parse("http://%zz") // want [SA1007] "http://%zz" is not a valid URL: parse "http://%zz": invalid URL escape "%zz"
        	_, _ = u.Parse(":foo") // want [SA1007] ":foo" is not a valid URL: parse ":foo": missing protocol scheme
        	_, _ = url.Parse("https://example.org/a?b=c")
        	_, _ = url.Parse(s)
        }
    """)

    fun testSA1010FindAll() = check("""
        package sc

        import "regexp"

        type R struct{}

        func (R) FindAllString(s string, n int) []string { return nil }

        func f(re *regexp.Regexp, s string, n int, r R) {
        	_ = re.FindAllString(s, 0) // want [SA1010] calling a FindAll method with n == 0 will return no results, did you mean -1?
        	_ = re.FindAll([]byte(s), 0) // want [SA1010] calling a FindAll method with n == 0 will return no results, did you mean -1?
        	_ = re.FindAllString(s, -1)
        	_ = re.FindAllString(s, n)
        	_ = r.FindAllString(s, 0)
        }
    """)

    fun testSA1012NilContext() = check("""
        package sc

        import (
        	"context"
        	c2 "context"
        )

        func run(ctx context.Context, n int) {}

        func run2(ctx c2.Context) {}

        func other(p *int) {}

        type S struct{}

        func (S) Do(ctx context.Context) {}

        func f() {
        	run(nil, 1) // want [SA1012] do not pass a nil Context, even if a function permits it; pass context.TODO if you are unsure about which Context to use
        	run2(nil) // want [SA1012] do not pass a nil Context, even if a function permits it; pass context.TODO if you are unsure about which Context to use
        	S{}.Do(nil) // want [SA1012] do not pass a nil Context, even if a function permits it; pass context.TODO if you are unsure about which Context to use
        	other(nil)
        	run(context.TODO(), 1)
        }
    """)

    fun testSA1013Seek() = check("""
        package sc

        import (
        	"io"
        	"os"
        )

        func f(file *os.File, s io.Seeker) {
        	_, _ = file.Seek(io.SeekStart, 0) // want [SA1013] the first argument of io.Seeker is the offset, but an io.Seek* constant is being used instead
        	_, _ = s.Seek(io.SeekEnd, 10) // want [SA1013] the first argument of io.Seeker is the offset, but an io.Seek* constant is being used instead
        	_, _ = file.Seek(0, io.SeekStart)
        	_, _ = file.Seek(int64(io.SeekCurrent), 0)
        }
    """)

    private val unmarshal = """
        package sc

        import (
        	"encoding/gob"
        	"encoding/json"
        	j "encoding/json"
        	"encoding/xml"
        	"io"
        )

        type T struct{ A int }

        func f(data []byte, r io.Reader, v T, p *T, a any, m map[string]int) {
        	_ = json.Unmarshal(data, v) // want [SA1014] json.Unmarshal expects to unmarshal into a pointer, but the provided value is not a pointer
        	_ = j.Unmarshal(data, m) // want [SA1014] json.Unmarshal expects to unmarshal into a pointer, but the provided value is not a pointer
        	_ = xml.Unmarshal(data, v) // want [SA1014] xml.Unmarshal expects to unmarshal into a pointer, but the provided value is not a pointer
        	_ = json.NewDecoder(r).Decode(v) // want [SA1014] Decode expects to unmarshal into a pointer, but the provided value is not a pointer
        	_ = gob.NewDecoder(r).Decode(v) // want [govet:unmarshal] call of Decode passes non-pointer
        	_ = json.Unmarshal(data, p)
        	_ = json.Unmarshal(data, a)
        	_ = json.Unmarshal(data, &v)
        	_ = json.Unmarshal(data, nil)
        }

        func g[X any](data []byte, x X) { _ = json.Unmarshal(data, x) }
    """

    fun testSA1014Unmarshal() = check(unmarshal)

    fun testGovetUnmarshalWhenSA1014IsOff() {
        settings.setEnabled("SA1014", false)
        check("""
            package sc

            import "encoding/json"

            type T struct{ A int }

            func f(data []byte, v T) {
            	_ = json.Unmarshal(data, v) // want [govet:unmarshal] call of Unmarshal passes non-pointer as second argument
            }
        """)
    }

    fun testSA1016UntrappableSignals() = check("""
        package sc

        import (
        	"os"
        	"os/signal"
        	sig "os/signal"
        	"syscall"
        )

        func f(c chan os.Signal) {
        	signal.Notify(c, os.Kill) // want [SA1016] os.Kill cannot be trapped (did you mean syscall.SIGTERM?)
        	sig.Ignore(syscall.SIGKILL) // want [SA1016] syscall.SIGKILL cannot be trapped (did you mean syscall.SIGTERM?)
        	signal.Reset(os.Signal(syscall.SIGSTOP)) // want [SA1016] syscall.SIGSTOP cannot be trapped
        	signal.Notify(c, os.Interrupt, syscall.SIGTERM)
        }
    """)

    private val signals = """
        package sc

        import (
        	"os"
        	"os/signal"
        	s2 "os/signal"
        )

        func f(param chan os.Signal) {
        	c1 := make(chan os.Signal)
        	signal.Notify(c1, os.Interrupt) // want [SA1017] the channel used with signal.Notify should be buffered
        	s2.Notify(make(chan os.Signal), os.Interrupt) // want [SA1017] the channel used with signal.Notify should be buffered
        	var c2 = make(chan os.Signal, 0)
        	signal.Notify(c2, os.Interrupt) // want [SA1017] the channel used with signal.Notify should be buffered
        	c3 := make(chan os.Signal, 1)
        	signal.Notify(c3, os.Interrupt)
        	c4 := make(chan os.Signal)
        	c4 = make(chan os.Signal, 1)
        	signal.Notify(c4, os.Interrupt)
        	signal.Notify(param, os.Interrupt)
        }
    """

    fun testSA1017UnbufferedSignalChannel() = check(signals)

    fun testSigchanyzerWhenSA1017IsOff() {
        settings.setEnabled("SA1017", false)
        check("""
            package sc

            import (
            	"os"
            	"os/signal"
            )

            func f() {
            	c1 := make(chan os.Signal)
            	signal.Notify(c1, os.Interrupt) // want [govet:sigchanyzer] misuse of unbuffered os.Signal channel as argument to signal.Notify
            	signal.Notify(make(chan os.Signal), os.Interrupt)
            	var c2 = make(chan os.Signal, 0)
            	signal.Notify(c2, os.Interrupt)
            }
        """)
    }

    fun testSA1018ReplaceZero() = check("""
        package sc

        import (
        	"bytes"
        	"strings"
        	s2 "strings"
        )

        func f(s string, b []byte, n int) {
        	_ = strings.Replace(s, "a", "b", 0) // want [SA1018] calling strings.Replace with n == 0 will return no results, did you mean -1?
        	_ = s2.Replace(s, "a", "b", 0) // want [SA1018] calling strings.Replace with n == 0 will return no results, did you mean -1?
        	_ = bytes.Replace(b, nil, nil, 0) // want [SA1018] calling bytes.Replace with n == 0 will return no results, did you mean -1?
        	_ = strings.Replace(s, "a", "b", -1)
        	_ = strings.Replace(s, "a", "b", n)
        }
    """)

    fun testSA1018DotImport() = check("""
        package sc

        import . "strings"

        func f(s string) {
        	_ = Replace(s, "a", "b", 0) // want [SA1018] calling strings.Replace with n == 0 will return no results, did you mean -1?
        }
    """)

    fun testSA1020ListenAddress() = check("""
        package sc

        import (
        	"net/http"
        	h "net/http"
        )

        func f(addr string) {
        	_ = http.ListenAndServe("localhost", nil) // want [SA1020] invalid port or service name in host:port pair
        	_ = h.ListenAndServeTLS(":99999", "c", "k", nil) // want [SA1020] invalid port or service name in host:port pair
        	_ = http.ListenAndServe(":8080", nil)
        	_ = http.ListenAndServe("localhost:http", nil)
        	_ = http.ListenAndServe(addr, nil)
        	_ = http.ListenAndServe("", nil)
        }
    """)

    fun testSA1021BytesEqualIp() = check("""
        package sc

        import (
        	"bytes"
        	b2 "bytes"
        	"net"
        )

        func f(a, b net.IP, x, y []byte) {
        	_ = bytes.Equal(a, b) // want [SA1021] use net.IP.Equal to compare net.IPs, not bytes.Equal
        	_ = b2.Equal(a, net.IPv4bcast) // want [SA1021] use net.IP.Equal to compare net.IPs, not bytes.Equal
        	_ = bytes.Equal(x, y)
        	_ = bytes.Equal(a, y)
        }
    """)

    fun testSA1024Cutset() = check("""
        package sc

        import (
        	"bytes"
        	"strings"
        	s2 "strings"
        )

        func f(s string, b []byte, cut string) {
        	_ = strings.Trim(s, "aaba") // want [SA1024] cutset contains duplicate characters
        	_ = s2.TrimLeft(s, "http://") // want [SA1024] cutset contains duplicate characters
        	_ = bytes.TrimRight(b, "xx") // want [SA1024] cutset contains duplicate characters
        	_ = strings.Trim(s, "ab")
        	_ = strings.TrimPrefix(s, "aa")
        	_ = strings.Trim(s, cut)
        }
    """)

    fun testSA1029ContextKey() = check("""
        package sc

        import (
        	"context"
        	c2 "context"
        )

        type key struct{}

        type name string

        func f(ctx context.Context, k string) {
        	_ = context.WithValue(ctx, "user", 1) // want [SA1029] should not use built-in type string as key for value; define your own type to avoid collisions
        	_ = c2.WithValue(ctx, k, 1) // want [SA1029] should not use built-in type string as key for value; define your own type to avoid collisions
        	_ = context.WithValue(ctx, 42, 1) // want [SA1029] should not use built-in type int as key for value; define your own type to avoid collisions
        	_ = context.WithValue(ctx, []int{}, 1) // want [SA1029] keys used with context.WithValue must be comparable, but type []int is not comparable
        	_ = context.WithValue(ctx, key{}, 1)
        	_ = context.WithValue(ctx, name("x"), 1)
        }
    """)

    fun testSA1030Strconv() = check("""
        package sc

        import (
        	"strconv"
        	sc2 "strconv"
        )

        func f(s string, base int) {
        	_, _ = strconv.ParseInt(s, 1, 64) // want [SA1030] 'base' must not be smaller than 2, unless it is 0
        	_, _ = sc2.ParseUint(s, 10, 65) // want [SA1030] 'bitSize' argument is invalid, must be within 0 and 64
        	_, _ = strconv.ParseFloat(s, 16) // want [SA1030] 'bitSize' argument is invalid, must be either 32 or 64
        	_ = strconv.FormatInt(1, 37) // want [SA1030] 'base' must not be larger than 36
        	_ = strconv.FormatFloat(1, 'z', -1, 64) // want [SA1030] 'fmt' argument is invalid: unknown format 'z'
        	_, _ = strconv.ParseComplex(s, 32) // want [SA1030] 'bitSize' argument is invalid, must be either 64 or 128
        	_, _ = strconv.ParseInt(s, 0, 64)
        	_, _ = strconv.ParseInt(s, base, 64)
        	_ = strconv.FormatFloat(1, 'f', -1, 32)
        }
    """)

    fun testSA1032ErrorsIs() = check("""
        package sc

        import (
        	"errors"
        	e2 "errors"
        	"io"
        )

        var ErrLocal = errors.New("x")

        func f(err error) {
        	_ = errors.Is(io.EOF, err) // want [SA1032] arguments have the wrong order
        	_ = e2.Is(ErrLocal, err) // want [SA1032] arguments have the wrong order
        	_ = errors.Is(err, io.EOF)
        	_ = errors.Is(io.EOF, ErrLocal)
        	_ = errors.Is(io.EOF, nil)
        }
    """)

    // ---- suppression and switching off

    fun testNolintAndLintIgnore() = check("""
        package sc

        import "strings"

        func f(s string) {
        	_ = strings.Replace(s, "a", "b", 0) //nolint:staticcheck
        	//lint:ignore SA1018 on purpose
        	_ = strings.Replace(s, "a", "b", 0)
        	_ = strings.Replace(s, "a", "b", 0) // want [SA1018] calling strings.Replace with n == 0 will return no results, did you mean -1?
        }
    """)

    fun testDisabledRule() {
        settings.setEnabled("SA1018", false)
        check("""
            package sc

            import "strings"

            func f(s string) {
            	_ = strings.Replace(s, "a", "b", 0)
            }
        """)
    }

    // ---- quick fixes

    fun testFixReplaceZero() = fix(
        "package sc\n\nimport \"strings\"\n\nfunc f(s string) string {\n\treturn strings.Replace(s, \"a\", \"b\", <caret>0)\n}",
        "Replace with -1",
        "package sc\n\nimport \"strings\"\n\nfunc f(s string) string {\n\treturn strings.Replace(s, \"a\", \"b\", -1)\n}",
    )

    fun testFixNilContextAddsImport() = fix(
        "package sc\n\nimport c2 \"context\"\n\nfunc run(ctx c2.Context) {}\n\nfunc f() {\n\trun(<caret>nil)\n}",
        "Use context.TODO",
        "package sc\n\nimport c2 \"context\"\n\nfunc run(ctx c2.Context) {}\n\nfunc f() {\n\trun(c2.TODO())\n}",
    )

    fun testFixNilContextWithoutImport() = fix(
        "package sc\n\nimport \"net/http\"\n\nfunc f() {\n\t_, _ = http.NewRequestWithContext(<caret>nil, \"GET\", \"/\", nil)\n}",
        "Use context.Background",
        "package sc\n\nimport (\n\t\"context\"\n\t\"net/http\"\n)\n\nfunc f() {\n\t_, _ = http.NewRequestWithContext(context.Background(), \"GET\", \"/\", nil)\n}",
    )

    fun testFixSwapSeekArguments() = fix(
        "package sc\n\nimport (\n\t\"io\"\n\t\"os\"\n)\n\nfunc f(file *os.File) {\n\t_, _ = file.Se<caret>ek(io.SeekStart, 10)\n}",
        "Swap arguments",
        "package sc\n\nimport (\n\t\"io\"\n\t\"os\"\n)\n\nfunc f(file *os.File) {\n\t_, _ = file.Seek(10, io.SeekStart)\n}",
    )

    fun testFixDynamicFormat() = fix(
        "package sc\n\nimport f2 \"fmt\"\n\nfunc f(s string) {\n\tf2.Pri<caret>ntf(s)\n}",
        "Use fmt.Print instead of fmt.Printf",
        "package sc\n\nimport f2 \"fmt\"\n\nfunc f(s string) {\n\tf2.Print(s)\n}",
    )

    fun testFixBufferedSignalChannel() = fix(
        "package sc\n\nimport (\n\t\"os\"\n\t\"os/signal\"\n)\n\nfunc f() {\n\tc := make(chan os.Signal)\n\tsignal.Notify(<caret>c, os.Interrupt)\n}",
        "Change to buffered channel",
        "package sc\n\nimport (\n\t\"os\"\n\t\"os/signal\"\n)\n\nfunc f() {\n\tc := make(chan os.Signal, 1)\n\tsignal.Notify(c, os.Interrupt)\n}",
    )

    fun testFixRemoveUntrappableSignal() = fix(
        "package sc\n\nimport (\n\t\"os\"\n\t\"os/signal\"\n)\n\nfunc f(c chan os.Signal) {\n\tsignal.Notify(c, os.Interrupt, <caret>os.Kill)\n}",
        "Remove os.Kill from list of arguments",
        "package sc\n\nimport (\n\t\"os\"\n\t\"os/signal\"\n)\n\nfunc f(c chan os.Signal) {\n\tsignal.Notify(c, os.Interrupt)\n}",
    )

    fun testFixBytesEqualIp() = fix(
        "package sc\n\nimport (\n\t\"bytes\"\n\t\"net\"\n)\n\nfunc f(a, b net.IP) bool {\n\treturn bytes.Eq<caret>ual(a, b)\n}",
        "Use net.IP.Equal",
        "package sc\n\nimport (\n\t\"bytes\"\n\t\"net\"\n)\n\nfunc f(a, b net.IP) bool {\n\treturn a.Equal(b)\n}",
    )

    fun testFixSplitExecCommand() = fix(
        "package sc\n\nimport \"os/exec\"\n\nfunc f() {\n\t_ = exec.Command(<caret>\"git status\")\n}",
        "Split into program name and arguments",
        "package sc\n\nimport \"os/exec\"\n\nfunc f() {\n\t_ = exec.Command(\"git\", \"status\")\n}",
    )

    fun testFixPassPointer() = fix(
        "package sc\n\nimport \"encoding/json\"\n\nfunc f(data []byte, v map[string]int) {\n\t_ = json.Unmarshal(data, <caret>v)\n}",
        "Pass a pointer",
        "package sc\n\nimport \"encoding/json\"\n\nfunc f(data []byte, v map[string]int) {\n\t_ = json.Unmarshal(data, &v)\n}",
    )

    private companion object {
        val IDS = listOf(
            "SA1000", "SA1004", "SA1005", "SA1006", "SA1007", "SA1010", "SA1012", "SA1013", "SA1014", "govet:unmarshal", "SA1016", "SA1017",
            "govet:sigchanyzer", "SA1018", "SA1020", "SA1021", "SA1024", "SA1029", "SA1030", "SA1032",
        )
        val B2 = Regex("""^\[(SA10\d\d|govet:unmarshal|govet:sigchanyzer)]""")
    }
}
