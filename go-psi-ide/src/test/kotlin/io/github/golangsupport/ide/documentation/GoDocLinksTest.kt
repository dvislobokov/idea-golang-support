package io.github.golangsupport.ide.documentation

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec

/** Doc links of Go 1.19 doc comments: parsing, references from comments, rename through them, and the links of Quick Documentation. */
class GoDocLinksTest : GoSemanticIdeTestBase() {

    private val source = """
        package d

        import "strings"

        // Point is a point: see [Point.Move], [Point.X], [Shape], [*Point], [strings.Builder] and [strings.Builder.Len].
        // Also [encoding/json.Marshal], [error], [strings], [Missing], a[i] and [the spec].
        //
        //	[Indented] is code.
        //
        // [the spec]: https://go.dev/ref/spec
        type Point struct{ X int }

        // Move moves; [Shape.Area] is elsewhere.
        func (p *Point) Move() {
        	// [Point] inside a body is no doc link
        }

        type Shape interface{ Area() float64 }
    """.trimIndent() + "\n"

    /** `text -> what it resolves to` for every doc link reference of the file's comments. */
    private fun references(file: PsiElement): List<String> =
        PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java).flatMap { c -> c.references.filterIsInstance<GoDocLinkReference>() }.map { ref ->
            ref.rangeInElement.substring(ref.element.text) + " -> " + describe(ref.resolve())
        }

    private fun describe(e: PsiElement?): String = when (e) {
        null -> "null"
        is GoImportSpec -> "import ${e.path}"
        is GoMethodDeclaration -> "method ${e.name} in ${e.containingFile.name}"
        is GoNamedElement -> "${e.javaClass.simpleName.removePrefix("Go").removeSuffix("Impl")} ${e.name} in ${e.containingFile.name}"
        else -> e.toString()
    }

    fun testParse() {
        val link = GoDocLinks.parse("golang.org/x/net/html.Node.Parent", 10)!!
        assertEquals("golang.org/x/net/html", link.path)
        assertEquals(listOf("Node", "Parent"), link.names)
        assertEquals(listOf(32, 37), link.nameRanges.map { it.startOffset })
        assertEquals(listOf("pkg", "T", "M"), GoDocLinks.parse("pkg.T.M", 0)!!.names)
        assertNull(GoDocLinks.parse("a.b.c.d", 0))
        assertNull(GoDocLinks.parse("encoding/json", 0))
        assertNull(GoDocLinks.parse("1abc", 0))
    }

    fun testReferencesResolve() {
        val file = myFixture.configureByText("d.go", source)
        assertEquals(
            listOf(
                "Point -> TypeSpec Point in d.go", "Move -> method Move in d.go",
                "Point -> TypeSpec Point in d.go", "X -> FieldDefinition X in d.go",
                "Shape -> TypeSpec Shape in d.go",
                "Point -> TypeSpec Point in d.go",
                "strings -> import strings", "Builder -> TypeSpec Builder in builder.go",
                "strings -> import strings", "Builder -> TypeSpec Builder in builder.go", "Len -> method Len in builder.go",
                "Marshal -> FunctionDeclaration Marshal in encode.go",
                "error -> TypeSpec error in builtin.go",
                "strings -> import strings",
                "Missing -> null",
                "Shape -> TypeSpec Shape in d.go", "Area -> MethodSpec Area in d.go",
            ),
            references(file),
        )
    }

    fun testNavigationFromALink() {
        myFixture.configureByText("n.go", "package n\n\n// Run calls [Hel<caret>per].\nfunc Run() {}\n\nfunc /*def*/Helper() {}\n")
        val target = myFixture.file.findReferenceAt(myFixture.caretOffset)!!.resolve()
        val def = myFixture.file.findElementAt(myFixture.file.text.indexOf("/*def*/") + "/*def*/".length)!!.parent
        assertSame(def, target)
        assertTrue(myFixture.findUsages(def).any { it.element is PsiComment })
    }

    fun testRenameUpdatesLinks() {
        val file = myFixture.configureByText(
            "r.go",
            "package r\n\n// Run calls [Helper], [T.Do] and [*T].\nfunc Run() {}\n\nfunc Helper() {}\n\ntype T struct{}\n\nfunc (T) Do() {}\n",
        ) as GoFile
        myFixture.renameElement(PsiTreeUtil.findChildrenOfType(file, GoFunctionDeclaration::class.java).first { it.name == "Helper" }, "Assist")
        myFixture.renameElement(PsiTreeUtil.findChildOfType(file, GoMethodDeclaration::class.java)!!, "Make")
        myFixture.renameElement(PsiTreeUtil.findChildOfType(file, GoTypeSpec::class.java)!!, "U")
        myFixture.checkResult("package r\n\n// Run calls [Assist], [U.Make] and [*U].\nfunc Run() {}\n\nfunc Assist() {}\n\ntype U struct{}\n\nfunc (U) Make() {}\n")
    }

    fun testRenameAtCaretInALink() {
        myFixture.configureByText("c.go", "package c\n\n// Run calls [Hel<caret>per].\nfunc Run() { Helper() }\n\nfunc Helper() {}\n")
        myFixture.renameElementAtCaret("Assist")
        myFixture.checkResult("package c\n\n// Run calls [Assist].\nfunc Run() { Assist() }\n\nfunc Assist() {}\n")
    }

    fun testQuickDocumentationLinks() {
        val file = myFixture.configureByText("d.go", source) as GoFile
        val point = PsiTreeUtil.findChildrenOfType(file, GoTypeSpec::class.java).first { it.name == "Point" }
        val html = GoDocumentationTarget(point).render(short = false)!!
        assertTrue(html, html.contains("<a href=\"psi_element://Point.Move\"><code>Point.Move</code></a>"))
        assertTrue(html, html.contains("<a href=\"psi_element://strings.Builder.Len\"><code>strings.Builder.Len</code></a>"))
        assertTrue(html, html.contains("<a href=\"psi_element://*Point\"><code>*Point</code></a>"))
        assertTrue(html, html.contains("<code>Missing</code>") && !html.contains("psi_element://Missing"))
        assertTrue(html, html.contains("<a href=\"https://go.dev/ref/spec\">the spec</a>"))

        val handler = GoDocLinkHandler()
        assertNotNull(handler.resolveLink(GoDocumentationTarget(point), "psi_element://Point.Move"))
        assertNull(handler.resolveLink(GoDocumentationTarget(point), "psi_element://Missing"))
        assertEquals("Move", (GoDocLinks.resolveText(point, "Point.Move") as GoNamedElement).name)
        assertEquals("Len", (GoDocLinks.resolveText(point, "strings.Builder.Len") as GoNamedElement).name)
    }
}
