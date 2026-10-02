package io.github.golangsupport.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.FileASTNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.BlockSupportImpl
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.LazyParseableElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.index.GoBuildTagsIndex
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.stubs.GoFileElementType
import io.github.golangsupport.lang.stubs.index.GoFunctionIndex
import java.nio.file.Files

/**
 * Lazy, reparseable function bodies (docs/GRAMMAR.md section N): bodies stay collapsed until
 * accessed, stub building and indexing never expand them, an edit inside a body re-parses only that
 * body (when [GoLazyBlockElementType.isReparseable] allows it), and every edit, incremental or not,
 * gives the same tree as parsing the new text from scratch.
 */
class GoLazyBodyTest : GoCodeInsightTestBase() {

    private val source = """
        package p

        import "fmt"

        type T struct{ n int }

        func A() int {
        	return 1
        }

        func B(x int) int {
        	y := x + 1 /*B*/
        	if y > 0 {
        		y--
        	}
        	g := func() int {
        		return y /*LIT*/
        	}
        	if f := func() int { return 2 /*HDR*/ }(); f > 0 {
        		fmt.Println(f)
        	}
        	_ = []func(){func() { /*LV*/ }}
        	return g()
        }

        func (t T) M() {
        	t.n++ /*M*/
        }

        var V = func() int {
        	return 3 /*PKG*/
        }

        	func Indented() {
        	_ = 1 /*IND*/
        }

        func C() {}
    """.trimIndent() + "\n"

    // --- laziness ---

    fun testBodiesStayCollapsedUntilAccessed() {
        val file = parse(source)
        val bodies = declarationBodies(file)
        assertEquals(5, bodies.size)
        for (body in bodies) assertFalse("collapsed after the file parse: ${body.text.take(20)}", body.isParsed)
        val b = function(file, "B").block!!
        assertEquals("statements of B", 6, b.statementList.size)
        assertTrue((b.node as LazyParseableElement).isParsed)
        assertEquals("only B was expanded", 1, declarationBodies(file).count { it.isParsed })
        // A function literal body inside B is itself lazy.
        val literal = PsiTreeUtil.findChildrenOfType(b, GoFunctionLit::class.java).first()
        assertTrue(literal.node.findChildByType(GoTypes.BLOCK) is LazyParseableElement)
    }

    fun testStubBuildingDoesNotParseBodies() {
        val text = Files.readString(GoTestUtil.goroot().resolve("src/net/http/server.go"))
        val file = parse(text)
        val before = GoLazyBlockElementType.parseCount()
        // (LazyParseableElement.setParsingAllowed(false) cannot be used: it fails on any child access
        // of a lazy node, even an already parsed one such as the file element.)
        val stub = GoFileElementType.INSTANCE.builder.buildStubTree(file)
        assertTrue("stubs were built", stub.childrenStubs.size > 100)
        assertEquals("no body was parsed while building stubs", before, GoLazyBlockElementType.parseCount())
        assertTrue(declarationBodies(file).none { it.isParsed })
    }

    fun testIndexingDoesNotParseBodies() {
        val before = GoLazyBlockElementType.parseCount()
        myFixture.addFileToProject("idx/a.go", source)
        val scope = GlobalSearchScope.allScope(project)
        assertNotEmpty(StubIndex.getElements(GoFunctionIndex.KEY, "B", project, scope, GoFunctionDeclaration::class.java))
        assertNotEmpty(FileBasedIndex.getInstance().getContainingFiles(GoFileImportsIndex.NAME, "fmt", scope))
        FileBasedIndex.getInstance().getAllKeys(GoBuildTagsIndex.NAME, project)
        assertEquals("indexing parsed no body", before, GoLazyBlockElementType.parseCount())
    }

    // --- incremental re-parse ---

    fun testEditInsideBodyReparsesOnlyThatBody() {
        val file = configure(source)
        DebugUtil.psiToString(file, true) // expand everything, like the highlighting pass
        val declarations = file.children.filter { it.node.elementType !== com.intellij.psi.TokenType.WHITE_SPACE }
        val bodies = file.children.filterIsInstance<GoFunctionOrMethodDeclaration>().associate { it.name to it.block }
        val bBody = function(file, "B").block!!
        val ifStatement = bBody.statementList[1]

        assertSame("re-parse root", bBody.node, reparseRoot(file, "/*B*/", "\n\tz := y * 2"))
        type(file, "/*B*/", "\n\tz := y * 2")
        assertTreeEqualsFresh(file)
        val after = file.children.filter { it.node.elementType !== com.intellij.psi.TokenType.WHITE_SPACE }
        assertEquals(declarations.size, after.size)
        for ((old, new) in declarations.zip(after)) assertSame("top-level PSI kept: ${old.text.take(20)}", old, new)
        for ((name, body) in bodies) assertSame("body of $name kept", body, function(file, name!!).block)
        assertSame("untouched statement of B kept", ifStatement, function(file, "B").block!!.statementList[2])
        assertEquals(7, function(file, "B").block!!.statementList.size)
    }

    fun testLiteralAndMethodBodiesAreReparseRoots() {
        val file = configure(source)
        val b = function(file, "B").block!!
        val literalBody = PsiTreeUtil.findChildrenOfType(b, GoFunctionLit::class.java).first { it.text.contains("/*LIT*/") }.node.findChildByType(GoTypes.BLOCK)
        assertSame("a literal in a statement", literalBody, reparseRoot(file, "/*LIT*/", "+ 1"))
        assertSame("a method body", function(file, "M").block!!.node, reparseRoot(file, "/*M*/", "\n\tt.n--"))
        assertSame("a literal in an if header re-parses the enclosing body", b.node, reparseRoot(file, "/*HDR*/", "+ 1"))
        assertSame("a literal in a literal value re-parses the enclosing body", b.node, reparseRoot(file, "/*LV*/", "x()"))
        assertNull("a package-level literal is not re-parseable", reparseRoot(file, "/*PKG*/", "+ 1"))
        assertNull("an indented declaration is not re-parseable", reparseRoot(file, "/*IND*/", "+ 1"))
    }

    fun testEditsThatChangeTheBodyExtentFallBackToAFullReparse() {
        val cases = listOf(
            "unbalanced '{'" to "if y > 1 {",
            "stray '}' closes the body early" to "}",
            "unterminated raw string" to "_ = `abc",
            "unterminated block comment" to "/* open",
            "func IDENT" to "\nfunc X() {",
            "declaration keyword at column 0" to "\nvar z = 1",
        )
        for ((name, insertion) in cases) {
            val file = configure(source)
            DebugUtil.psiToString(file, true)
            val root = reparseRoot(file, "/*B*/", insertion)
            assertTrue("$name: no incremental re-parse of B's body ($root)", root == null || root.elementType !== GoTypes.BLOCK)
            type(file, "/*B*/", insertion)
            assertTreeEqualsFresh(file, name)
        }
    }

    fun testBodyBecomingEmptyOrBalancedIsIncremental() {
        val file = configure(source)
        DebugUtil.psiToString(file, true)
        val a = function(file, "A").block!!
        assertSame(a.node, reparseRoot(file, "\treturn 1\n", ""))
        type(file, "\treturn 1\n", "")
        assertTreeEqualsFresh(file, "empty body")
        assertEquals("{\n}", function(file, "A").block!!.text)

        val b = function(file, "B").block!!
        val balanced = "\n\tfor {\n\t\tbreak\n\t}\n\t_ = map[string]int{\"}\": 1}"
        assertSame(b.node, reparseRoot(file, "/*B*/", balanced))
        type(file, "/*B*/", balanced)
        assertTreeEqualsFresh(file, "balanced braces, '}' in a string")
        assertSame(b, function(file, "B").block)
    }

    fun testUnclosedBodyRecovers() {
        // The body of B loses its closing brace: it ends before `func (t T) M`, which still parses cleanly.
        val text = source.replace("\treturn g()\n}\n", "\treturn g()\n")
        val file = parse(text)
        val m = function(file, "M")
        assertNull(PsiTreeUtil.findChildOfType(m, com.intellij.psi.PsiErrorElement::class.java))
        assertNotNull(PsiTreeUtil.findChildOfType(function(file, "B"), com.intellij.psi.PsiErrorElement::class.java))
        assertEquals(text, file.text)
    }

    // --- helpers ---

    /** A parsed non-physical file (not marked as a copy: that walks, and so expands, the whole tree). */
    private fun parse(text: String): GoFile =
        (PsiFileFactory.getInstance(project).createFileFromText("a.go", GoLanguage, text, false, false) as GoFile).also { it.node.firstChildNode }

    private fun configure(text: String): GoFile = myFixture.configureByText("a.go", text) as GoFile

    private fun function(file: PsiFile, name: String): GoFunctionOrMethodDeclaration =
        PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java).single { it.name == name }

    private fun declarationBodies(file: PsiFile): List<LazyParseableElement> =
        PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java)
            .mapNotNull { it.node.findChildByType(GoTypes.BLOCK) as? LazyParseableElement }

    /** The node the platform would re-parse for replacing [marker] with [marker] + [insertion], or null for a full re-parse. */
    private fun reparseRoot(file: PsiFile, marker: String, insertion: String): ASTNode? {
        val text = file.text
        val at = text.indexOf(marker)
        assertTrue("marker $marker", at >= 0)
        val (range, newText) = if (insertion.isEmpty()) {
            TextRange(at, at + marker.length) to text.removeRange(at, at + marker.length)
        } else {
            val offset = at + marker.length
            TextRange(offset, offset) to text.substring(0, offset) + insertion + text.substring(offset)
        }
        return BlockSupportImpl.findReparseableNodeAndReparseIt(file as PsiFileImpl, file.node as FileASTNode, range, newText)?.first
    }

    /** Inserts [insertion] after [marker] (or deletes [marker] when [insertion] is empty) and commits. */
    private fun type(file: PsiFile, marker: String, insertion: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val at = document.text.indexOf(marker)
        WriteCommandAction.runWriteCommandAction(project) {
            if (insertion.isEmpty()) document.deleteString(at, at + marker.length)
            else document.insertString(at + marker.length, insertion)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun assertTreeEqualsFresh(file: PsiFile, message: String = "") {
        val fresh = parse(file.text)
        assertEquals("$message: re-parsed tree differs from a fresh parse", dump(fresh), dump(file))
    }

    private fun dump(element: PsiElement): String = DebugUtil.psiToString(element, true, true)
}
