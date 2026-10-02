package io.github.golangsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoPostfixExpressions
import io.github.golangsupport.lang.GoPostfixKinds
import io.github.golangsupport.lang.psi.GoFile

/** The postfix templates: where each applies by the PSI and the type of the expression, and what it writes. */
class GoPostfixTemplatesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    /** Accepts every stop of the running template with its default. */
    private fun finishTemplate() = WriteCommandAction.runWriteCommandAction(project) { TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false) }

    private val declarations = """
        type Item struct{ Name string }

        func load() (int, error) { return 0, nil }
        func pair() (int, string, error) { return 0, "", nil }
        func save() error { return nil }
        func count() int { return 0 }
        func work() {}
    """.trimIndent()

    private fun source(signature: String, line: String) = "package a\n\n$declarations\n\nfunc f($signature) {\n\t$line\n}\n"

    /** Whether template [key] applies at `<caret>` of [line] in a function with the parameters [signature]. */
    private fun applies(key: String, signature: String, line: String): Boolean {
        myFixture.configureByText("a.go", source(signature, line))
        val file = myFixture.file as GoFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val kind = GoPostfixKinds.ALL.first { it.key == key }
        val subject = GoPostfixExpressions.subject(file, myFixture.editor.document, myFixture.caretOffset, kind.statement) ?: return false
        return kind.applies(subject)
    }

    /** Types `.key` and Tab after the expression at `<caret>`, accepts every stop with its default, and returns the function body. */
    private fun expand(key: String, signature: String, line: String, results: String = ""): String {
        val text = source(signature, line).let { if (results.isEmpty()) it else it.replace("func f($signature) {", "func f($signature) $results {") }
        myFixture.configureByText("a.go", text)
        myFixture.type(".$key\t")
        finishTemplate()
        val result = myFixture.editor.document.text
        return result.substring(result.indexOf("{\n", result.indexOf("func f(")) + 2, result.lastIndexOf("\n}"))
    }

    fun testIfAndNotApplyToBooleansOnly() {
        assertTrue(applies("if", "ok bool", "ok<caret>"))
        assertFalse(applies("if", "n int", "n<caret>"))
        assertTrue(applies("not", "a, b int", "_ = a == b<caret>"))
        assertFalse(applies("not", "s string", "_ = s<caret>"))
        assertEquals("\tif ok {\n\t\t\n\t}", expand("if", "ok bool", "ok<caret>"))
    }

    fun testStatementTemplatesNeedAnExpressionStatement() {
        assertFalse("not after :=", applies("if", "ok bool", "x := ok<caret>"))
        assertTrue("an expression template is fine there", applies("par", "ok bool", "x := ok<caret>"))
    }

    fun testNotNegatesAComparisonInParenthesesAndDropsADoubleNegation() {
        assertEquals("\t_ = !(a == b)", expand("not", "a, b int", "_ = a == b<caret>"))
        assertEquals("\t_ = ok", expand("not", "ok bool", "_ = !ok<caret>"))
    }

    fun testParWrapsTheWholeExpression() = assertEquals("\t_ = (a + b)", expand("par", "a, b int", "_ = a + b<caret>"))

    fun testNilAndNotNilApplyToNillableTypes() {
        assertTrue(applies("nil", "p *Item", "p<caret>"))
        assertTrue(applies("nn", "m map[string]int", "m<caret>"))
        assertTrue(applies("nn", "e error", "e<caret>"))
        assertFalse(applies("nil", "n int", "n<caret>"))
        assertFalse(applies("nn", "i Item", "i<caret>"))
        assertEquals("\tif p != nil {\n\t\t\n\t}", expand("nn", "p *Item", "p<caret>"))
        assertEquals("\tif p == nil {\n\t\t\n\t}", expand("nil", "p *Item", "p<caret>"))
    }

    fun testErrAppliesToErrorsAndCallsEndingWithAnError() {
        assertTrue(applies("err", "", "save()<caret>"))
        assertTrue(applies("err", "", "load()<caret>"))
        assertTrue(applies("err", "e error", "e<caret>"))
        assertFalse(applies("err", "", "count()<caret>"))
        assertFalse(applies("err", "n int", "n<caret>"))
    }

    fun testErrOnACallOfSeveralResultsDeclaresThemAndChecksTheError() {
        assertEquals("\tv, err := load()\n\tif err != nil {\n\t\treturn 0, err\n\t}", expand("err", "", "load()<caret>", "(int, error)"))
        assertEquals("\tv, v2, err := pair()\n\tif err != nil {\n\t\treturn err\n\t}", expand("err", "", "pair()<caret>", "error"))
    }

    fun testErrOnACallOfAnErrorChecksItInTheIf() =
        assertEquals("\tif err := save(); err != nil {\n\t\treturn \"\", err\n\t}", expand("err", "", "save()<caret>", "(string, error)"))

    fun testForGoesOverWhatRangeTakesWithVariablesByTheType() {
        assertFalse(applies("for", "ok bool", "ok<caret>"))
        assertTrue(applies("for", "n int", "n<caret>"))
        assertEquals("\tfor k, v := range m {\n\t\t\n\t}", expand("for", "m map[string]int", "m<caret>"))
        assertEquals("\tfor v := range ch {\n\t\t\n\t}", expand("for", "ch chan int", "ch<caret>"))
        assertEquals("\tfor i := range n {\n\t\t\n\t}", expand("for", "n int", "n<caret>"))
        assertEquals("\tfor _, v := range items {\n\t\t\n\t}", expand("for", "items []Item", "items<caret>"))
        assertFalse("a send-only channel", applies("for", "ch chan<- int", "ch<caret>"))
    }

    fun testForiAndForrCountToALengthOrAnInteger() {
        assertEquals("\tfor i := 0; i < n; i++ {\n\t\t\n\t}", expand("fori", "n int", "n<caret>"))
        assertEquals("\tfor i := 0; i < len(xs); i++ {\n\t\t\n\t}", expand("fori", "xs []int", "xs<caret>"))
        assertEquals("\tfor i := len(xs) - 1; i >= 0; i-- {\n\t\t\n\t}", expand("forr", "xs []int", "xs<caret>"))
        assertFalse(applies("forr", "ok bool", "ok<caret>"))
    }

    fun testVarDeclaresEveryResultOfACall() {
        assertEquals("\tv, err := load()", expand("var", "", "load()<caret>"))
        assertEquals("\tv := count()", expand("var", "", "count()<caret>"))
        assertFalse("no result", applies("var", "", "work()<caret>"))
    }

    fun testReturnPutsTheValueAmongTheResultsByItsType() {
        assertEquals("\treturn 0, e", expand("return", "e error", "e<caret>", "(int, error)"))
        assertEquals("\treturn n, nil", expand("return", "n int", "n<caret>", "(int, error)"))
        assertEquals("\treturn n", expand("return", "n int", "n<caret>", "int"))
    }

    fun testLenSortAndAppendWantSlicesOrLengths() {
        assertTrue(applies("len", "s string", "_ = s<caret>"))
        assertFalse(applies("len", "n int", "_ = n<caret>"))
        assertTrue(applies("sort", "xs []int", "xs<caret>"))
        assertFalse(applies("sort", "m map[int]int", "m<caret>"))
        assertFalse(applies("append", "n int", "n<caret>"))
    }

    fun testSortUsesSlicesForOrderedElementsAndImportsIt() {
        myFixture.configureByText("a.go", source("xs []int", "xs<caret>"))
        myFixture.type(".sort\t")
        finishTemplate()
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\tslices.Sort(xs)") && text.contains("import \"slices\""))
    }

    fun testSortOfStructsUsesSortSlice() {
        myFixture.configureByText("a.go", source("items []Item", "items<caret>"))
        myFixture.type(".sort\t")
        finishTemplate()
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("sort.Slice(items, func(i, j int) bool {") && text.contains("import \"sort\""))
    }

    fun testPrintImportsFmt() {
        myFixture.configureByText("a.go", source("n int", "n<caret>"))
        myFixture.type(".print\t")
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\tfmt.Println(n)") && text.contains("import \"fmt\""))
    }

    fun testGoAndDeferWantACall() {
        assertTrue(applies("go", "", "work()<caret>"))
        assertFalse(applies("defer", "n int", "n<caret>"))
        assertEquals("\tdefer work()", expand("defer", "", "work()<caret>"))
    }

    fun testWrapIsAnExpressionOverAnError() {
        assertTrue(applies("wrap", "e error", "_ = e<caret>"))
        assertFalse(applies("wrap", "n int", "_ = n<caret>"))
    }

    fun testAnUncommittedDocumentIsReadByItsText() {
        myFixture.configureByText("a.go", source("ok bool", "<caret>"))
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(myFixture.caretOffset, "items[0].Name") }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))
        val offset = myFixture.caretOffset + "items[0].Name".length
        val subject = GoPostfixExpressions.subject(myFixture.file as GoFile, document, offset, statement = true)!!
        assertEquals("items[0].Name", subject.text)
        assertNull(subject.expression)
    }

    fun testAnUnresolvedExpressionKeepsTheTemplates() {
        assertTrue(applies("if", "", "unknown<caret>"))
        assertTrue(applies("for", "", "unknown<caret>"))
        assertTrue(applies("err", "", "unknown()<caret>"))
    }
}
