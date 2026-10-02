package io.github.golangsupport.ide.documentation

import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause

/** Quick documentation HTML (goldens), doc comment syntax, parameter info and type info. */
class GoDocumentationTest : GoSemanticIdeTestBase() {

    override val testDataSubdir: String = "documentation"

    private fun target(element: PsiElement): GoDocumentationTarget =
        PsiDocumentationTargetProvider.EP_NAME.extensionList.firstNotNullOf { it.documentationTarget(element, null) } as GoDocumentationTarget

    private fun html(element: PsiElement): String {
        val t = target(element)
        assertNotNull("no documentation for $element", t.computeDocumentation())
        return t.render(short = false)!!
    }

    private inline fun <reified T : GoNamedElement> named(file: GoFile, name: String): T =
        PsiTreeUtil.findChildrenOfType(file, T::class.java).first { it.name == name }

    fun testDeclarationsGolden() {
        val file = myFixture.addFileToProject("doc/doc.go", readTestData("doc/doc.go")) as GoFile
        val elements: List<Pair<String, PsiElement>> = listOf(
            "package clause" to file.packageClause!!,
            "const Pi" to named<io.github.golangsupport.lang.psi.GoConstDefinition>(file, "Pi"),
            "const Max" to named<io.github.golangsupport.lang.psi.GoConstDefinition>(file, "Max"),
            "const Min" to named<io.github.golangsupport.lang.psi.GoConstDefinition>(file, "Min"),
            "var Default" to named<io.github.golangsupport.lang.psi.GoVarDefinition>(file, "Default"),
            "type Point" to named<io.github.golangsupport.lang.psi.GoTypeSpec>(file, "Point"),
            "field X" to named<io.github.golangsupport.lang.psi.GoFieldDefinition>(file, "X"),
            "field Y" to named<io.github.golangsupport.lang.psi.GoFieldDefinition>(file, "Y"),
            "embedded Reader" to named<io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition>(file, "Reader"),
            "method Move" to named<io.github.golangsupport.lang.psi.GoMethodDeclaration>(file, "Move"),
            "type Shape" to named<io.github.golangsupport.lang.psi.GoTypeSpec>(file, "Shape"),
            "method spec Area" to named<io.github.golangsupport.lang.psi.GoMethodSpec>(file, "Area"),
            "func Map" to named<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(file, "Map"),
            "func Sum" to named<io.github.golangsupport.lang.psi.GoFunctionDeclaration>(file, "Sum"),
            "param dx" to named<io.github.golangsupport.lang.psi.GoParamDefinition>(file, "dx"),
            "type param T" to named<io.github.golangsupport.lang.psi.GoTypeParamDefinition>(file, "T"),
            "local count" to named<io.github.golangsupport.lang.psi.GoVarDefinition>(file, "count"),
        )
        val report = elements.joinToString("\n\n") { (title, e) -> "== $title\n" + html(e) }
        assertGolden("doc/expected.html", report)
    }

    fun testHintCollapsesBodies() {
        val file = myFixture.addFileToProject("doc/doc.go", readTestData("doc/doc.go")) as GoFile
        assertEquals(
            "<div class='definition'><pre>type Shape interface{...}</pre></div>",
            target(named<io.github.golangsupport.lang.psi.GoTypeSpec>(file, "Shape")).computeDocumentationHint(),
        )
    }

    fun testGorootFunctionAndPackage() {
        myFixture.configureByText("main.go", "package main\n\nimport \"fmt\"\n\nfunc main() { fmt.Println() }\n")
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, io.github.golangsupport.lang.psi.GoReferenceExpression::class.java).first { it.text == "fmt.Println" }
        val println = call.reference!!.resolve()!!
        val doc = html(println)
        assertTrue(doc, doc.startsWith("<div class='definition'><pre>func Println(a ...any) (n int, err error)</pre></div>"))
        assertTrue(doc, doc.contains("<div class='content'><p>Println formats"))
        assertTrue(doc, doc.contains("<code>fmt</code>") && doc.contains("print.go"))
        // `fmt` as a qualifier resolves to the import spec: the package doc from $GOROOT/src/fmt/doc.go.
        val spec = (call.expression as io.github.golangsupport.lang.psi.GoReferenceExpression).reference!!.resolve()!!
        val pkg = html(spec)
        assertTrue(pkg, pkg.startsWith("<div class='definition'><pre>package fmt</pre></div>"))
        assertTrue(pkg, pkg.contains("Package fmt implements formatted I/O"))
        assertTrue(pkg, pkg.contains("<h3>Printing</h3>"))
        assertTrue(pkg, pkg.contains("<pre><code>"))
    }

    fun testBuiltinDocumentation() {
        myFixture.configureByText("main.go", "package main\n\nfunc main() { _ = len(\"x\") }\n")
        val ref = PsiTreeUtil.findChildrenOfType(myFixture.file, io.github.golangsupport.lang.psi.GoReferenceExpression::class.java).first { it.text == "len" }
        val doc = html(ref.reference!!.resolve()!!)
        assertTrue(doc, doc.contains("func len(v Type) int"))
        assertTrue(doc, doc.contains("The len built-in function returns the length of v"))
    }

    fun testDocCommentSyntax() {
        val text = """
            Para one
            continues.

            # Heading

            - a
            - b
              wrapped

             1. one
             2. two

            	code <x>
            	  indented

            [Name], [pkg.Name] and https://example.com/x.
            Link to [the spec] and ``quoted''.

            [the spec]: https://go.dev/ref/spec
        """.trimIndent()
        assertEquals(
            """
            <p>Para one
            continues.</p>
            <h3>Heading</h3>
            <ul><li>a</li><li>b
            wrapped</li></ul>
            <ol><li>one</li><li>two</li></ol>
            <pre><code>code &lt;x&gt;
              indented</code></pre>
            <p><code>Name</code>, <code>pkg.Name</code> and <a href="https://example.com/x">https://example.com/x</a>.
            Link to <a href="https://go.dev/ref/spec">the spec</a> and &ldquo;quoted&rdquo;.</p>
            """.trimIndent(),
            GoDocHtml.toHtml(text),
        )
    }

    // --- parameter info ---

    private fun parameterInfo(text: String): Triple<String, String, Boolean> {
        myFixture.configureByText("p.go", text)
        val handler = GoParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.editor, myFixture.file)
        val list = handler.findElementForParameterInfo(create) ?: return Triple("<none>", "", false)
        val item = create.itemsToShow!!.single() as GoParameterInfoItem
        val update = MockUpdateParameterInfoContext(myFixture.editor, myFixture.file)
        handler.updateParameterInfo(list, update)
        val ui = MockParameterInfoUIContext<GoArgumentList>(list)
        ui.currentParameterIndex = update.currentParameter
        handler.updateUI(item, ui)
        val highlighted = if (ui.highlightStart >= 0) ui.text.substring(ui.highlightStart, ui.highlightEnd) else ""
        return Triple(ui.text, highlighted, true)
    }

    fun testParameterInfoPlainCall() {
        val (text, current) = parameterInfo("package p\n\nfunc f(a int, b string) {}\n\nfunc g() { f(1, <caret>) }\n")
        assertEquals("a int, b string", text)
        assertEquals("b string", current)
    }

    fun testParameterInfoVariadic() {
        val (text, current) = parameterInfo("package p\n\nfunc f(format string, args ...any) {}\n\nfunc g() { f(\"\", 1, 2, <caret>3) }\n")
        assertEquals("format string, args ...any", text)
        assertEquals("args ...any", current)
    }

    fun testParameterInfoMethodAndNoParameters() {
        val (text, current) = parameterInfo("package p\n\ntype T struct{}\n\nfunc (T) M(x float64) {}\nfunc h() {}\n\nfunc g(t T) { t.M(<caret>) }\n")
        assertEquals("x float64", text)
        assertEquals("x float64", current)
        assertEquals("<no parameters>", parameterInfo("package p\n\nfunc h() {}\n\nfunc g() { h(<caret>) }\n").first)
    }

    fun testParameterInfoGeneric() {
        val (text, current) = parameterInfo("package p\n\nfunc Map[T, U any](s []T, f func(T) U) []U { return nil }\n\nfunc g() { Map([]int{1}, <caret>) }\n")
        assertEquals("[T any, U any] s []T, f func(T) U", text)
        assertEquals("f func(T) U", current)
    }

    fun testParameterInfoGoroot() {
        val (text, current) = parameterInfo("package p\n\nimport \"strings\"\n\nvar _ = strings.Repeat(<caret>\"a\", 2)\n")
        assertEquals("s string, count int", text)
        assertEquals("s string", current)
    }

    // --- type info ---

    fun testExpressionTypeInfo() {
        myFixture.configureByText("t.go", "package t\n\nfunc f(m map[string][]int) {\n\tx := m[\"a\"<caret>]\n\t_ = x\n}\n")
        val provider = GoExpressionTypeProvider()
        val exprs = provider.getExpressionsAt(myFixture.file.findElementAt(myFixture.caretOffset - 1)!!)
        val types = exprs.map { it.text to provider.getInformationHint(it) }
        assertEquals(listOf("\"a\"" to "untyped string", "m[\"a\"]" to "[]int"), types)
        assertEquals("map[string][]int", GoExpressionTypeProvider.typeText(PsiTreeUtil.findChildrenOfType(myFixture.file, GoExpression::class.java).first { it.text == "m" }))
    }

    fun testPackageClauseDocumentationFromDocGo() {
        myFixture.addFileToProject("pkg/a.go", "package pkg\n\nfunc A() {}\n")
        myFixture.addFileToProject("pkg/doc.go", "// Package pkg does things.\npackage pkg\n")
        val a = myFixture.addFileToProject("pkg/b.go", "package pkg\n") as GoFile
        val doc = html(a.packageClause as GoPackageClause)
        assertTrue(doc, doc.contains("<p>Package pkg does things.</p>"))
    }
}
