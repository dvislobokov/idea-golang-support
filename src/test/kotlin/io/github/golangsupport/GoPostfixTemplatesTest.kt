package io.github.golangsupport

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoPostfixExpressions
import io.github.golangsupport.lang.GoPostfixKinds
import io.github.golangsupport.lang.GoPostfixNames
import io.github.golangsupport.lang.GoPostfixTemplateProvider
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
        type Circle struct{ Radius float64 }
        func (c Circle) Area() float64 { return 0 }
        func loadUser() (Item, error) { return Item{}, nil }
        type byName []Item
        func (b byName) Len() int { return 0 }
        func (b byName) Less(i, j int) bool { return false }
        func (b byName) Swap(i, j int) {}
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
        assertEquals("the value is named after the slice", "\tfor _, item := range items {\n\t\t\n\t}", expand("for", "items []Item", "items<caret>"))
        assertEquals("no plural", "\tfor _, v := range data {\n\t\t\n\t}", expand("for", "data []Item", "data<caret>"))
        assertFalse("a send-only channel", applies("for", "ch chan<- int", "ch<caret>"))
    }

    fun testForiAndForrevCountToALengthOrAnInteger() {
        assertEquals("\tfor i := 0; i < n; i++ {\n\t\t\n\t}", expand("fori", "n int", "n<caret>"))
        assertEquals("\tfor i := 0; i < len(xs); i++ {\n\t\t\n\t}", expand("fori", "xs []int", "xs<caret>"))
        assertEquals("\tfor i := len(xs) - 1; i >= 0; i-- {\n\t\t\n\t}", expand("forrev", "xs []int", "xs<caret>"))
        assertFalse(applies("forrev", "ok bool", "ok<caret>"))
    }

    fun testForrNamesTheIndexAndTheElementAfterTheSlice() {
        assertEquals("\tfor i, name := range names {\n\t\t\n\t}", expand("forr", "names []string", "names<caret>"))
        assertEquals("\tfor i, entry := range entries {\n\t\t\n\t}", expand("forr", "entries []Item", "entries<caret>"))
        assertEquals("\tfor i, box := range boxes {\n\t\t\n\t}", expand("forr", "boxes []Item", "boxes<caret>"))
        assertEquals("no plural", "\tfor i, item := range data {\n\t\t\n\t}", expand("forr", "data []Item", "data<caret>"))
        assertEquals("taken names get a number", "\tfor i1, name1 := range names {\n\t\t\n\t}", expand("forr", "names []string, name string, i int", "names<caret>"))
        assertEquals("a map keeps k, v", "\tfor k, v := range users {\n\t\t\n\t}", expand("forr", "users map[string]Item", "users<caret>"))
        assertFalse(applies("forr", "ok bool", "ok<caret>"))
    }

    fun testVarDeclaresEveryResultOfACall() {
        assertEquals("\tv, err := load()", expand("var", "", "load()<caret>"))
        assertEquals("\tv := count()", expand("var", "", "count()<caret>"))
        assertFalse("no result", applies("var", "", "work()<caret>"))
    }

    fun testVarTakesTheNameFromTheExpression() {
        assertEquals("\tarea := c.Area()", expand("var", "c Circle", "c.Area()<caret>"))
        assertEquals("\tradius := c.Radius", expand("var", "c Circle", "c.Radius<caret>"))
        assertEquals("a verb is cut off", "\tuser, err := loadUser()", expand("var", "", "loadUser()<caret>"))
        assertEquals("an element of a slice", "\titem := items[0]", expand("var", "items []Item", "items[0]<caret>"))
        assertEquals("a taken name gets a number", "\tarea1 := c.Area()", expand("var", "c Circle, area float64", "c.Area()<caret>"))
        assertEquals("a plain name says nothing new", "\tv := n", expand("var", "n int", "n<caret>"))
    }

    fun testTheNamesOfTheExpressionByText() {
        assertEquals("area", GoPostfixNames.variableName("c.Area()"))
        assertEquals("server", GoPostfixNames.variableName("http.NewServer(addr)"))
        assertEquals("open", GoPostfixNames.variableName("os.Open(path)"))
        assertEquals("id", GoPostfixNames.variableName("u.ID"))
        assertEquals("httpClient", GoPostfixNames.variableName("s.HTTPClient"))
        assertNull("a literal", GoPostfixNames.variableName("\"text\""))
        assertNull("a keyword", GoPostfixNames.variableName("x.Type"))
        assertEquals("category", GoPostfixNames.elementName("categories"))
        assertEquals("match", GoPostfixNames.elementName("s.Matches"))
        assertEquals("url", GoPostfixNames.elementName("URLs"))
        assertEquals("name", GoPostfixNames.elementName("getNames()"))
        assertNull("not a plural", GoPostfixNames.elementName("status"))
        assertNull("not a plural", GoPostfixNames.elementName("list"))
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

    /** The whole file after `.key` + Tab at `<caret>` with every stop at its default. */
    private fun expandFile(key: String, signature: String, line: String, results: String = ""): String {
        val text = source(signature, line).let { if (results.isEmpty()) it else it.replace("func f($signature) {", "func f($signature) $results {") }
        myFixture.configureByText("a.go", text)
        myFixture.type(".$key\t")
        finishTemplate()
        return myFixture.editor.document.text
    }

    fun testSortUsesSlicesForOtherOrderedElementsAndImportsIt() {
        val text = expandFile("sort", "xs []int64", "xs<caret>")
        assertTrue(text, text.contains("\tslices.Sort(xs)") && text.contains("import \"slices\""))
    }

    fun testSortPicksTheFunctionOfPackageSortByTheType() {
        assertTrue(expandFile("sort", "xs []int", "xs<caret>").let { it.contains("\tsort.Ints(xs)") && it.contains("import \"sort\"") })
        assertTrue(expandFile("sort", "xs []string", "xs<caret>").contains("\tsort.Strings(xs)"))
        assertTrue(expandFile("sort", "xs []float64", "xs<caret>").contains("\tsort.Float64s(xs)"))
        assertTrue(expandFile("sort", "b byName", "b<caret>").contains("\tsort.Sort(b)"))
    }

    fun testNewBuiltinWrappers() {
        assertEquals("\t_ = cap(xs)", expand("cap", "xs []int", "_ = xs<caret>"))
        assertEquals("\t_ = copy(xs, )", expand("copy", "xs []int", "_ = xs<caret>"))
        assertEquals("\t_ = append(xs, )", expand("append", "xs []int", "_ = xs<caret>"))
        assertEquals("\txs = append(xs, )", expand("aappend", "xs []int", "xs<caret>"))
        assertEquals("\txs = append(xs, )", expand("appendAssign", "xs []int", "xs<caret>"))
        assertEquals("\txs = append(xs[:i], xs[i+1:]...)", expand("remove", "xs []int", "xs<caret>"))
        assertEquals("\tclose(ch)", expand("close", "ch chan int", "ch<caret>"))
        assertEquals("\tdelete(m, )", expand("delete", "m map[string]int", "m<caret>"))
        assertEquals("\t_ = complex(x, )", expand("complex", "x float64", "_ = x<caret>"))
        assertEquals("\t_ = real(z)", expand("real", "z complex128", "_ = z<caret>"))
        assertEquals("\t_ = imag(z)", expand("imag", "z complex128", "_ = z<caret>"))
        assertEquals("\tprintln(n)", expand("println", "n int", "n<caret>"))
    }

    fun testNewBuiltinWrappersWantTheirTypes() {
        assertFalse(applies("cap", "m map[int]int", "_ = m<caret>"))
        assertFalse(applies("close", "ch <-chan int", "ch<caret>"))
        assertFalse(applies("close", "xs []int", "xs<caret>"))
        assertFalse(applies("delete", "xs []int", "xs<caret>"))
        assertFalse(applies("remove", "m map[int]int", "m<caret>"))
        assertFalse(applies("complex", "n int", "_ = n<caret>"))
        assertFalse(applies("real", "x float64", "_ = x<caret>"))
        assertFalse(applies("println", "", "work()<caret>"))
    }

    fun testAddressAndDereference() {
        for (key in listOf("&", "p", "pointer")) assertEquals(key, "\t_ = &i", expand(key, "i Item", "_ = i<caret>"))
        for (key in listOf("*", "d", "dereference")) assertEquals(key, "\t_ = *p", expand(key, "p *Item", "_ = p<caret>"))
        assertEquals("\t_ = !ok", expand("!", "ok bool", "_ = ok<caret>"))
        assertFalse("not a pointer", applies("d", "i Item", "_ = i<caret>"))
        assertFalse("a call is not addressable", applies("p", "", "_ = count()<caret>"))
        assertFalse("a literal", applies("&", "", "_ = 42<caret>"))
        assertFalse(applies("!", "n int", "_ = n<caret>"))
    }

    fun testErrorsAsAndIsImportErrors() {
        val text = expandFile("as", "e error", "_ = e<caret>")
        assertTrue(text, text.contains("\t_ = errors.As(e, &target)") && text.contains("import \"errors\""))
        assertTrue(expandFile("is", "e error", "_ = e<caret>").contains("\t_ = errors.Is(e, )"))
    }

    fun testErrorOnlyKeysAreNotOfferedOnAnInt() {
        for (key in listOf("as", "is", "wrap", "err", "nil", "nn", "notnil")) assertFalse(key, applies(key, "n int", if (key == "err" || key.startsWith("n")) "n<caret>" else "_ = n<caret>"))
        for (key in listOf("as", "is", "nn")) assertTrue(key, applies(key, "e error", if (key == "nn") "e<caret>" else "_ = e<caret>"))
    }

    fun testParseIntAndParseFloatCheckTheError() {
        val text = expandFile("parseInt", "s string", "s<caret>", "error")
        assertTrue(text, text.contains("\tn, err := strconv.ParseInt(s, 10, 64)\n\tif err != nil {\n\t\treturn err\n\t}") && text.contains("import \"strconv\""))
        // `f` is the function around
        assertTrue(expandFile("parseFloat", "s string", "s<caret>", "error").contains("\tf1, err := strconv.ParseFloat(s, 64)"))
        assertFalse(applies("parseInt", "n int", "n<caret>"))
    }

    fun testAPackageOrATypeIsNoSubject() {
        myFixture.configureByText("a.go", "package a\n\nimport \"fmt\"\n\ntype T struct{}\n\nfunc f() {\n\tfmt<caret>\n\t_ = fmt.Sprint()\n}\n")
        val file = myFixture.file as GoFile
        assertNull(GoPostfixExpressions.subject(file, myFixture.editor.document, myFixture.caretOffset, statement = false))
        myFixture.configureByText("a.go", "package a\n\ntype T struct{}\n\nfunc f() {\n\tT<caret>\n}\n")
        assertNull(GoPostfixExpressions.subject(myFixture.file as GoFile, myFixture.editor.document, myFixture.caretOffset, statement = false))
    }

    fun testEveryKeyHasItsDescriptionAndSymbolsAreKeysOfTheirOwn() {
        val templates = GoPostfixTemplateProvider().templates.associateBy { it.key }
        assertEquals("Adds the & operator before the expression.", templates.getValue(".p").description)
        assertTrue("`x.!` reads back to the symbol", "!" in templates && "&" in templates && "*" in templates)
        assertEquals("ids are unique", templates.size, templates.values.map { it.id }.toSet().size)
        val html = javaClass.getResource("/postfixTemplates/GoPostfixTemplate/description.html")!!.readText().replace("&amp;", "&")
        for (kind in GoPostfixKinds.ALL) assertTrue(kind.key, "<code>.${kind.key}</code>" in html)
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
