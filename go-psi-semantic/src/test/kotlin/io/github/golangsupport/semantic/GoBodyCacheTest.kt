package io.github.golangsupport.semantic

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Per-function-body inference cache ([GoBodyCache]): values inside a body live in one store per
 * top-level function (function literals included), invalidated by edits inside that function and
 * by out-of-block edits of the package, not by edits in other functions of the file.
 */
class GoBodyCacheTest : GoSemanticTestBase() {

    override val group: String get() = "types"

    private val trackers get() = GoTrackers.getInstance(project)
    private val resolver get() = GoResolver.getInstance(project)

    private fun edit(file: GoFile, marker: String, replacement: String) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val offset = doc.text.indexOf(marker)
        assertTrue("marker $marker not found", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(offset, offset + marker.length, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
    }

    private fun function(file: GoFile, name: String): GoFunctionOrMethodDeclaration =
        PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java).single { it.name == name }

    private fun expressions(file: GoFile, name: String): List<GoExpression> =
        PsiTreeUtil.findChildrenOfType(function(file, name).block, GoExpression::class.java).toList()

    /** The last expression with [text] inside function [name] (the innermost of equal texts is fine for references). */
    private fun expr(file: GoFile, name: String, text: String): GoExpression =
        expressions(file, name).lastOrNull { it.text == text } ?: error("no expression '$text' in $name")

    private fun typeText(e: GoExpression): String = GoTypeRenderer.render(semantic.typeOf(e))

    private fun bodyCount(file: GoFile, name: String): Long = trackers.forBody(function(file, name).block!!).modificationCount

    private fun add(text: String): GoFile = myFixture.addFileToProject("bc/a.go", text) as GoFile

    private val source = """
        package bc

        type S struct {
        	X int
        	Y /*YTYPE*/string
        }

        var Global /*GTYPE*/int

        /*BETWEEN*/

        func F() {
        	x := /*XV*/1
        	a := []S{{X: x}}
        	/*F*/
        	fl := func() []S {
        		y := /*LV*/1
        		_ = x
        		_ = y
        		return a
        	}
        	_ = fl
        }

        func G() {
        	m := map[string]S{}
        	g := m["k"]
        	_ = g.Y
        	_ = Global
        	_ = v
        	f := func(p []S) []S { return p }
        	_ = f
        }

        func (s *S) M() {
        	z := /*MZ*/1
        	h := func() []S {
        		w := /*MW*/1
        		_ = z
        		_ = w
        		return []S{*s}
        	}
        	_ = h
        }

        var PL = func() int {
        	q := /*PQ*/1
        	return q
        }
    """.trimIndent() + "\n"

    /** Warms every expression type and reference of [name]; returns (expression, type, resolve result) for composite types. */
    private fun warm(file: GoFile, name: String): List<Triple<GoExpression, Any, Any?>> =
        expressions(file, name).map { e ->
            Triple(e, semantic.typeOf(e), (e as? GoReferenceExpression)?.let(resolver::resolveReferenceExpression))
        }

    fun testEditInOneFunctionKeepsTheOtherFunctions() {
        val file = add(source)
        val g = warm(file, "G")
        val composite = g.filter { (_, t, _) -> t !is GoBasicType && t !is GoUnknownType }
        assertTrue("too few composite types in G: ${composite.size}", composite.size >= 4)
        val gBody = function(file, "G").block!!
        val gCount = bodyCount(file, "G")
        val gStore = GoBodyCache.existingStore(gBody)
        assertNotNull("G's store was filled", gStore)
        val fCount = bodyCount(file, "F")

        edit(file, "/*F*/", "a = append(a, S{})")
        assertSame("G's PSI survives an edit in F", gBody, function(file, "G").block)
        assertEquals("an edit in F keeps G's tracker", gCount, bodyCount(file, "G"))
        assertTrue("an edit in F bumps F's tracker", bodyCount(file, "F") > fCount)
        assertSame("an edit in F keeps G's store", gStore, GoBodyCache.existingStore(gBody))
        for ((e, t, r) in g) {
            assertSame("type of '${e.text}' in G recomputed after an edit in F", t, semantic.typeOf(e))
            if (r != null) assertSame("resolve of '${e.text}' in G recomputed after an edit in F", r, resolver.resolveReferenceExpression(e as GoReferenceExpression))
        }

        edit(file, "/*LV*/1", "/*LV*/\"s\"")
        assertEquals("an edit in a literal of F keeps G's tracker", gCount, bodyCount(file, "G"))
        for ((e, t, _) in composite) assertSame("type of '${e.text}' in G recomputed after an edit in a literal of F", t, semantic.typeOf(e))
    }

    fun testEditInsideFunctionUpdatesItsTypes() {
        val file = add(source)
        val x = expr(file, "F", "x")
        assertNotNull("'x' inside the literal", PsiTreeUtil.getParentOfType(x, GoFunctionLit::class.java))
        assertEquals("int", typeText(x))
        assertEquals("int", typeText(expr(file, "F", "y")))
        assertEquals("[]S", typeText(expr(file, "F", "a")))

        edit(file, "/*XV*/1", "/*XV*/\"s\"")
        assertEquals("a use inside the literal sees the new type", "string", typeText(expr(file, "F", "x")))

        edit(file, "/*LV*/1", "/*LV*/2.5")
        assertEquals("an edit inside the literal updates the literal's types", "float64", typeText(expr(file, "F", "y")))
    }

    fun testMethodBodyAndLiteralBehaveLikeFunctions() {
        val file = add(source)
        val zUse = expr(file, "M", "z")
        val wUse = expr(file, "M", "w")
        val mBody = function(file, "M").block!!
        assertSame("a literal in a method belongs to the method", mBody, GoPsiUtil.outermostBody(wUse))
        assertSame("a function literal in a function belongs to the function", function(file, "F").block, GoPsiUtil.outermostBody(expr(file, "F", "y")))
        assertEquals("int", typeText(zUse))
        assertEquals("int", typeText(wUse))
        val g = warm(file, "G").filter { (_, t, _) -> t !is GoBasicType && t !is GoUnknownType }
        val gCount = bodyCount(file, "G")
        val mCount = bodyCount(file, "M")

        edit(file, "/*MW*/1", "/*MW*/'c'")
        assertTrue("an edit in a literal of M bumps M's tracker", bodyCount(file, "M") > mCount)
        assertEquals("rune", typeText(expr(file, "M", "w")))
        edit(file, "/*MZ*/1", "/*MZ*/true")
        assertEquals("bool", typeText(expr(file, "M", "z")))
        assertEquals("edits in M keep G's tracker", gCount, bodyCount(file, "G"))
        for ((e, t, _) in g) assertSame("type of '${e.text}' in G recomputed after an edit in M", t, semantic.typeOf(e))
    }

    fun testPackageLevelLiteralHasItsOwnBody() {
        val file = add(source)
        val literal = PsiTreeUtil.findChildrenOfType(file, GoFunctionLit::class.java).single { it.text.contains("/*PQ*/") }
        val q = PsiTreeUtil.findChildrenOfType(literal.block, GoExpression::class.java).last { it.text == "q" }
        assertSame("a package-level literal is its own body", literal.block, GoPsiUtil.outermostBody(q))
        assertEquals("int", typeText(q))
        val g = warm(file, "G").filter { (_, t, _) -> t !is GoBasicType && t !is GoUnknownType }
        val gCount = bodyCount(file, "G")

        edit(file, "/*PQ*/1", "/*PQ*/\"s\"")
        val newLiteral = PsiTreeUtil.findChildrenOfType(file, GoFunctionLit::class.java).single { it.text.contains("/*PQ*/") }
        val newQ = PsiTreeUtil.findChildrenOfType(newLiteral.block, GoExpression::class.java).last { it.text == "q" }
        assertEquals("string", typeText(newQ))
        assertEquals("an edit in a package-level literal keeps G's tracker", gCount, bodyCount(file, "G"))
        for ((e, t, _) in g) assertSame("type of '${e.text}' in G recomputed after an edit in a package-level literal", t, semantic.typeOf(e))
    }

    fun testPackageLevelEditInvalidatesBodies() {
        val file = add(source)
        val other = myFixture.addFileToProject("bc/b.go", "package bc\n\ntype T struct {\n\tN /*NTYPE*/int\n}\n\nfunc H(t T) {\n\t_ = t.N\n}\n") as GoFile
        assertEquals("int", typeText(expr(file, "G", "Global")))
        assertEquals("string", typeText(expr(file, "G", "g.Y")))
        assertEquals("int", typeText(expr(other, "H", "t.N")))

        edit(file, "/*GTYPE*/int", "/*GTYPE*/float32")
        assertEquals("a package-level var edit reaches the body", "float32", typeText(expr(file, "G", "Global")))
        edit(file, "/*YTYPE*/string", "/*YTYPE*/bool")
        assertEquals("a field type edit reaches the body", "bool", typeText(expr(file, "G", "g.Y")))
        edit(other, "/*NTYPE*/int", "/*NTYPE*/uint8")
        assertEquals("an edit in another file of the package reaches the body", "uint8", typeText(expr(other, "H", "t.N")))
    }

    /** Every expression type of [file], rendered, from the caches and after dropping them. */
    private fun assertNoStaleTypes(file: GoFile, what: String) {
        val all = PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java).toList()
        val cached = all.map(::typeText)
        trackers.invalidateAll()
        val fresh = all.map(::typeText)
        for (i in all.indices) assertEquals("$what: stale type of '${all[i].text}'", fresh[i], cached[i])
    }

    fun testUnattributedEditsLeaveNoStaleTypes() {
        val file = add(source)
        val v = expr(file, "G", "v")
        assertTrue("'v' is unresolved before", semantic.typeOf(v) is GoUnknownType)
        PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java).forEach { semantic.typeOf(it) }

        // Typing between functions: a new package-level declaration that a body uses.
        edit(file, "/*BETWEEN*/", "var v = \"s\"")
        assertEquals("a declaration typed between functions is seen in G", "string", typeText(expr(file, "G", "v")))
        assertNoStaleTypes(file, "after typing between functions")

        // Breaking braces: F's closing brace removed, so G's text is parsed into F; then restored.
        PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java).forEach { semantic.typeOf(it) }
        edit(file, "\t_ = fl\n}", "\t_ = fl\n")
        assertNoStaleTypes(file, "after breaking braces")
        edit(file, "\t_ = fl\n", "\t_ = fl\n}")
        assertEquals("string", typeText(expr(file, "G", "v")))
        assertEquals("[]S", typeText(expr(file, "F", "a")))
        assertNoStaleTypes(file, "after restoring braces")
    }

    fun testGenericChangeWithoutPreciseEventsDropsAllBodiesOfTheFile() {
        val file = add(source)
        warm(file, "F")
        warm(file, "G")
        val bodies = listOf(function(file, "F").block!!, function(file, "G").block!!)
        assertTrue(bodies.all { GoBodyCache.existingStore(it) != null })
        val manager = com.intellij.psi.PsiManager.getInstance(project) as com.intellij.psi.impl.PsiManagerImpl
        WriteCommandAction.runWriteCommandAction(project) {
            val event = com.intellij.psi.impl.PsiTreeChangeEventImpl(manager)
            event.parent = file
            event.setFile(file)
            event.isGenericChange = true
            manager.childrenChanged(event)
        }
        assertTrue("an unattributed change drops every body store of the file", bodies.all { GoBodyCache.existingStore(it) == null })
    }

    fun testBodyValuesDoNotUsePerElementCachedValues() {
        val file = add(source)
        val g = function(file, "G")
        warm(file, "G")
        val store = GoBodyCache.existingStore(g.block as GoBlock)
        assertNotNull(store)
        assertTrue("G's values are in its store: ${store!!.size}", store.size >= expressions(file, "G").size)
    }
    /** The node the platform re-parses for replacing [marker] by [replacement] in [file], or null for a full re-parse. */
    private fun reparseRoot(file: GoFile, marker: String, replacement: String): com.intellij.lang.ASTNode? {
        val text = file.text
        val at = text.indexOf(marker)
        val newText = text.substring(0, at) + replacement + text.substring(at + marker.length)
        return com.intellij.psi.impl.BlockSupportImpl.findReparseableNodeAndReparseIt(
            file as com.intellij.psi.impl.source.PsiFileImpl, file.node as com.intellij.lang.FileASTNode,
            com.intellij.openapi.util.TextRange(at, at + marker.length), newText,
        )?.first
    }

    /** Parents of the precise PSI events of [action] (the generic file-level `childrenChanged` excluded). */
    private fun eventParents(action: () -> Unit): List<com.intellij.psi.PsiElement> {
        val parents = ArrayList<com.intellij.psi.PsiElement>()
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.psi.PsiManager.getInstance(project).addPsiTreeChangeListener(object : com.intellij.psi.PsiTreeChangeAdapter() {
            fun record(e: com.intellij.psi.PsiTreeChangeEvent) {
                if ((e as? com.intellij.psi.impl.PsiTreeChangeEventImpl)?.isGenericChange == true) return
                e.parent?.let(parents::add)
            }
            override fun childAdded(event: com.intellij.psi.PsiTreeChangeEvent) = record(event)
            override fun childRemoved(event: com.intellij.psi.PsiTreeChangeEvent) = record(event)
            override fun childReplaced(event: com.intellij.psi.PsiTreeChangeEvent) = record(event)
            override fun childrenChanged(event: com.intellij.psi.PsiTreeChangeEvent) = record(event)
        }, disposable)
        try {
            action()
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
        return parents
    }

    /**
     * Lazy bodies (docs/GRAMMAR.md section N): an edit inside F re-parses only F's body; the platform
     * merges the new body into the old one, so F's body block (the store key) survives, the PSI events
     * are inside F's body (its tracker bumps, the package's does not), and G keeps its store and its
     * values.
     */
    fun testIncrementalBodyReparseIsAttributedToThatBody() {
        val file = add(source)
        val g = warm(file, "G").filter { (_, t, _) -> t !is GoBasicType && t !is GoUnknownType }
        assertTrue(g.size >= 4)
        assertEquals("[]S", typeText(expr(file, "F", "a")))
        val fBody = function(file, "F").block!!
        val gBody = function(file, "G").block!!
        val gStore = GoBodyCache.existingStore(gBody)
        assertNotNull(gStore)
        val fCount = bodyCount(file, "F")
        val gCount = bodyCount(file, "G")
        val packageCount = trackers.packageDependencies(file).filterIsInstance<com.intellij.openapi.util.ModificationTracker>().sumOf { it.modificationCount }

        assertSame("the edit re-parses F's body only", fBody.node, reparseRoot(file, "/*F*/", "b := a[0]"))
        val parents = eventParents { edit(file, "/*F*/", "b := a[0]\n\t_ = b") }
        assertTrue("events were sent", parents.isNotEmpty())
        for (p in parents) assertSame("event at ${p.node.elementType} is inside F's body", fBody, GoPsiUtil.outermostBody(p) ?: p)

        assertSame("F's body block survives the re-parse", fBody, function(file, "F").block)
        assertSame("G's body block survives", gBody, function(file, "G").block)
        assertTrue("F's tracker bumped", bodyCount(file, "F") > fCount)
        assertEquals("G's tracker kept", gCount, bodyCount(file, "G"))
        assertEquals("no out-of-block change", packageCount,
            trackers.packageDependencies(file).filterIsInstance<com.intellij.openapi.util.ModificationTracker>().sumOf { it.modificationCount })
        assertSame("G's store kept", gStore, GoBodyCache.existingStore(gBody))
        for ((e, t, _) in g) assertSame("type of '${e.text}' in G kept", t, semantic.typeOf(e))
        assertEquals("F's new statement is typed", "S", typeText(expr(file, "F", "b")))
        assertNoStaleTypes(file, "after an incremental body re-parse")
    }

    /** An edit that unbalances F's braces is a full re-parse; G's PSI survives and nothing is stale. */
    fun testFullReparseOfABodyEditKeepsOtherBodiesCorrect() {
        val file = add(source)
        warm(file, "G")
        warm(file, "F")
        val gBody = function(file, "G").block!!
        assertNull("an unbalanced brace is not an incremental re-parse", reparseRoot(file, "/*F*/", "if true {"))
        edit(file, "/*F*/", "if true {")
        assertSame("G's body block survives a full re-parse", gBody, function(file, "G").block)
        assertNoStaleTypes(file, "after a full re-parse")
        edit(file, "if true {", "/*F*/")
        assertEquals("[]S", typeText(expr(file, "F", "a")))
        assertNoStaleTypes(file, "after restoring the brace")
    }
}
