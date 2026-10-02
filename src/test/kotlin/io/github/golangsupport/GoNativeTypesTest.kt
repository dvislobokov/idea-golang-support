package io.github.golangsupport

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.lang.GoChannelReceiveCompletionContributor
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoIdiomTypes
import io.github.golangsupport.lang.GoMakeCompletionContributor
import io.github.golangsupport.lang.GoNativeSignatureProvider
import io.github.golangsupport.lang.GoReturnValues
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Paths

/**
 * The helpers that moved from the text onto the types of go-psi (migration step 9, part S): the signature provider of the linter's fixes,
 * the values of `return`, the grey idioms that the types decide, `make(` and the comma-ok receive. The toolchain is pinned to the local
 * GOROOT without a `go` binary (as in the go-psi test bases), the language server is off.
 */
class GoNativeTypesTest : BasePlatformTestCase() {
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

    private fun configure(text: String): GoFile = myFixture.configureByText("a.go", text) as GoFile

    private val caret: Int get() = myFixture.caretOffset

    // --- the signature provider ---

    fun testTheNativeSignatureProviderCountsTheResultsOfTheCall() {
        configure(
            """
            package main

            type Store struct{}

            func (s *Store) Load(name string) (int, string, error) { return 0, "", nil }

            func two() (int, error) { return 0, nil }

            func main() {
            	s := &Store{}
            	two()
            	s.Load("x")
            	missing()
            }
            """.trimIndent(),
        )
        val text = myFixture.editor.document.text
        val file = myFixture.file.virtualFile
        val provider = GoNativeSignatureProvider()
        assertEquals(2, provider.resultCount(project, file, text.indexOf("two()\n\ts.Load")))
        assertEquals(3, provider.resultCount(project, file, text.indexOf("Load(\"x\")")))
        assertNull("an unresolved call is left to gopls", provider.resultCount(project, file, text.indexOf("missing()")))
    }

    // --- return values ---

    private fun returnValues(text: String): GoReturnValues.Values? {
        configure(text)
        return GoReturnValues.forReturn(myFixture.file.findElementAt(caret - 1)!!)
    }

    fun testInsideAnErrorCheckTheZeroValuesAndTheCheckedError() {
        val values = returnValues("package main\n\nfunc load() (int, string, error) {\n\tn := 3\n\terr := run()\n\tif err != nil {\n\t\treturn x<caret>\n\t}\n\treturn n, \"\", nil\n}\n\nfunc run() error { return nil }\n")
        assertEquals("0, \"\", err", values?.plain)
        assertNull("fmt is not imported", values?.wrapped)
    }

    fun testElsewhereTheVariablesOfTheResultTypes() {
        val values = returnValues(
            "package main\n\nimport \"fmt\"\n\ntype Point struct{ X int }\n\nfunc count() (int, error) { return 0, nil }\n\n" +
                "func f() (Point, int, error) {\n\tn, err := count()\n\treturn x<caret>\n}\n\nvar _ = fmt.Sprint\n",
        )
        assertEquals("Point{}, n, err", values?.plain)
        assertEquals("Point{}, n, fmt.Errorf(\"count: %w\", err)", values?.wrapped)
    }

    fun testOneResultIsNotOfferedAndNoFunctionIsLeftToTheText() {
        assertSame(GoReturnValues.NONE, returnValues("package main\n\nfunc f() error {\n\treturn x<caret>\n}\n"))
    }

    fun testTheCompletionListOffersTheValues() {
        configure("package main\n\nfunc f() (int, error) {\n\terr := g()\n\tif err != nil {\n\t\treturn <caret>\n\t}\n\treturn 1, nil\n}\n\nfunc g() error { return nil }\n")
        myFixture.completeBasic()
        val labels = myFixture.lookupElements.orEmpty().map { LookupElementPresentation.renderElement(it).itemText ?: it.lookupString }
        assertTrue(labels.toString(), "0, err" in labels)
    }

    // --- grey idioms decided by the types ---

    private fun suggest(text: String): String? {
        val file = configure(text)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val document = myFixture.editor.document
        return GoIdioms.suggest(document.immutableCharSequence, caret, "\t", GoIdiomTypes.of(file, document, caret))
    }

    fun testSelectCasesFromTheScope() {
        val suggestion = suggest(
            "package main\n\nimport (\n\t\"context\"\n\t\"time\"\n)\n\nfunc run(ctx context.Context, in <-chan int, out chan<- int) (int, error) {\n" +
                "\tt := time.NewTimer(time.Second)\n\tselect {\n\t<caret>\n\t}\n}\n",
        ) ?: error("no suggestion")
        val done = suggestion.indexOf("case <-ctx.Done():")
        val timer = suggestion.indexOf("case <-t.C:")
        val channel = suggestion.indexOf("case v := <-in:")
        assertTrue(suggestion, done >= 0 && timer > done && channel > timer)
        assertTrue(suggestion, suggestion.contains("return 0, ctx.Err()"))
        assertFalse("a send-only channel is not received from: $suggestion", suggestion.contains("out"))
    }

    fun testAVariableNamedCtxThatIsNotAContextGivesNoContextCase() {
        val suggestion = suggest("package main\n\nfunc run(ctx int, ch chan string) {\n\tselect {\n\t<caret>\n\t}\n}\n") ?: error("no suggestion")
        assertTrue(suggestion, suggestion.startsWith("case v := <-ch:"))
        assertFalse(suggestion, suggestion.contains("Done"))
    }

    fun testNothingWhenTheCasesAreThereOrNothingIsInScope() {
        assertNull(suggest("package main\n\nfunc run(ch chan string) {\n\tselect {\n\t<caret>\n\tcase <-ch:\n\t}\n}\n"))
        assertNull(suggest("package main\n\nfunc run(n int) {\n\tselect {\n\t<caret>\n\t}\n}\n"))
    }

    fun testForWithAChannelGetsTheSelect() {
        val suggestion = suggest("package main\n\nfunc run(ch chan string) {\n\tfor {\n\t\t<caret>\n\t}\n}\n") ?: error("no suggestion")
        assertEquals("select {\n\t\tcase v := <-ch:\n\t\t}", suggestion)
    }

    fun testSwitchOverAnEnumListsItsConstants() {
        val suggestion = suggest(
            "package main\n\ntype Color int\n\nconst (\n\tRed Color = iota\n\tGreen\n\tBlue\n)\n\nconst Other = 3\n\nfunc f(c Color) {\n\tswitch c {\n\t<caret>\n\t}\n}\n",
        )
        assertEquals("case Red:\n\tcase Green:\n\tcase Blue:", suggestion)
    }

    fun testTypeSwitchListsTheImplementations() {
        val suggestion = suggest(
            "package main\n\ntype Shape interface{ Area() float64 }\n\ntype Square struct{}\n\nfunc (Square) Area() float64 { return 1 }\n\n" +
                "type Circle struct{}\n\nfunc (*Circle) Area() float64 { return 2 }\n\nfunc f(s Shape) {\n\tswitch v := s.(type) {\n\t<caret>\n\t}\n}\n",
        ) ?: error("no suggestion")
        assertTrue(suggestion, suggestion.contains("case Square:") && suggestion.contains("case *Circle:"))
    }

    fun testDeferCloseOnlyForWhatHasCloseError() {
        val opened = "package main\n\ntype Res struct{}\n\nfunc (Res) Close() error { return nil }\n\ntype Plain struct{}\n\n" +
            "func open() (Res, error) { return Res{}, nil }\n\nfunc plain() (Plain, error) { return Plain{}, nil }\n\n"
        assertEquals("defer r.Close()", suggest(opened + "func f() error {\n\tr, err := open()\n\tif err != nil {\n\t\treturn err\n\t}\n\t<caret>\n\treturn nil\n}\n"))
        assertNull(suggest(opened + "func f() error {\n\tr, err := plain()\n\tif err != nil {\n\t\treturn err\n\t}\n\t<caret>\n\t_ = r\n\treturn nil\n}\n"))
    }

    fun testDeferUnlockOnlyWhereThereIsAnUnlock() {
        val types = "package main\n\ntype Mu struct{}\n\nfunc (*Mu) Lock() {}\nfunc (*Mu) Unlock() {}\n\ntype FileLock struct{}\n\nfunc (FileLock) Lock() {}\n\n"
        assertEquals("defer mu.Unlock()", suggest(types + "func f() {\n\tvar mu Mu\n\tmu.Lock()\n\t<caret>\n}\n"))
        assertNull(suggest(types + "func f() {\n\tvar l FileLock\n\tl.Lock()\n\t<caret>\n}\n"))
    }

    fun testErrorCheckOnlyAfterACallThatReturnsAnErrorLast() {
        val calls = "package main\n\nfunc count() (int, error) { return 0, nil }\n\nfunc pair() (int, bool) { return 0, true }\n\n"
        // a blank line without an indent of its own: the suggestion brings the indent of the line above
        assertEquals("\tif err != nil {\n\t\treturn 0, err\n\t}", suggest(calls + "func f() (int, error) {\n\tn, err := count()\n<caret>\n\treturn n, nil\n}\n"))
        assertNull(suggest(calls + "func f() (int, error) {\n\tn, errored := pair()\n<caret>\n\treturn n, nil\n}\n"))
    }

    // --- make and the receive ---

    private fun makeItems(text: String): List<String>? {
        configure(text)
        return GoMakeCompletionContributor.arguments(myFixture.file.findElementAt(caret - 1)!!)
    }

    fun testMakeOffersTheExpectedType() {
        assertEquals(listOf("chan int"), makeItems("package main\n\nfunc f() {\n\tvar ch chan int = make(x<caret>)\n\t_ = ch\n}\n"))
        assertEquals(
            listOf("[]string", "[]string, 0, len(names)"),
            makeItems("package main\n\nfunc f(names []string) {\n\tvar out []string\n\tout = make(x<caret>)\n\t_ = out\n}\n"),
        )
        assertNull("no expected type", makeItems("package main\n\nfunc f() {\n\tout := make(x<caret>)\n\t_ = out\n}\n"))
    }

    fun testCommaOkReceiveOnAChannelOnly() {
        val file = configure("package main\n\nfunc f(ch chan int, n int) {\n\t<-ch<caret>\n}\n")
        assertEquals(listOf("v, ok := <-ch"), GoChannelReceiveCompletionContributor.receives(file, myFixture.editor.document.immutableCharSequence, caret))
        val other = configure("package main\n\nfunc f(ch chan int, n int) {\n\t<-n<caret>\n}\n")
        assertNull(GoChannelReceiveCompletionContributor.receives(other, myFixture.editor.document.immutableCharSequence, caret))
    }
}
