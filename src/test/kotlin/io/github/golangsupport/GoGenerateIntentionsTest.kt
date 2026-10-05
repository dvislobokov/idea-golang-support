package io.github.golangsupport

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.build.GoGenerateDirectiveIntention
import io.github.golangsupport.build.GoGenerateFileIntention
import io.github.golangsupport.build.GoGenerateIntention
import io.github.golangsupport.build.GoGeneratePackageIntention

/**
 * Alt+Enter on a `//go:generate` line as GoLand 2026.2.3 shows it (G10): Go Generate File, Go generate '<import path>', Go generate '<command>',
 * offered on directive lines only, and the `go` arguments. No `go` runs.
 */
class GoGenerateIntentionsTest : BasePlatformTestCase() {
    private val names = listOf("Go Generate File", "Go generate 'example.com/playground/internal/probe2'", "Go generate 'stringer -type=Level'")

    private val source = """
        package p

        //go:generate stringer -type=Level
        type Level int

        // go:generate not a directive
        func f() {
        	//go:generate indented: go generate skips it
        }
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("go.mod", "module example.com/playground\n\ngo 1.22\n")
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("internal/probe2/level.go", source).virtualFile)
    }

    private fun offeredAt(needle: String, delta: Int = 0): List<String> {
        val at = source.indexOf(needle).also { assertTrue(needle, it >= 0) } + delta
        myFixture.editor.caretModel.moveToOffset(at)
        // the popup orders by priority first (HIGH, NORMAL, LOW: testPriorities), the fixture keeps the order of registration
        val ours = myFixture.availableIntentions.map { it.text }.filter { it == "Go Generate File" || it.startsWith("Go generate '") }
        return ours.sortedBy { if (it == "Go Generate File") 0 else if (it.startsWith("Go generate 'example.com")) 1 else 2 }
    }

    fun testOfferedOnTheDirectiveLine() {
        assertEquals(names, offeredAt("//go:generate stringer"))
        assertEquals(names, offeredAt("-type=Level"))
        assertEquals(names, offeredAt("Level\ntype", 5))
    }

    fun testOnlyOursAndNoGutterDuplicates() {
        val texts = run { offeredAt("//go:generate stringer"); myFixture.availableIntentions.map { it.text } }
        assertEquals(names.toSet(), texts.filter { it.contains("generate", ignoreCase = true) && !it.startsWith("Disable ") }.toSet())
    }

    fun testNotOfferedElsewhere() {
        assertEquals(emptyList<String>(), offeredAt("type Level"))
        assertEquals(emptyList<String>(), offeredAt("go:generate not"))
        assertEquals(emptyList<String>(), offeredAt("indented"))
        assertEquals(emptyList<String>(), offeredAt("package p"))
    }

    fun testDirectiveAtTheCaret() {
        val file = myFixture.configureByText("level.go", source)
        assertEquals("//go:generate stringer -type=Level", GoGenerateIntention.directiveAt(file, source.indexOf("stringer")))
        assertNull(GoGenerateIntention.directiveAt(file, source.indexOf("indented")))
        assertEquals("echo hi", GoGenerateIntention.command("//go:generate echo hi"))
        assertEquals("echo hi", GoGenerateIntention.command("//go:generate\techo hi "))
    }

    fun testPriorities() {
        assertEquals(PriorityAction.Priority.HIGH, GoGenerateFileIntention().priority)
        assertEquals(PriorityAction.Priority.NORMAL, GoGeneratePackageIntention().priority)
        assertEquals(PriorityAction.Priority.LOW, GoGenerateDirectiveIntention().priority)
    }

    fun testArguments() {
        val directive = "//go:generate stringer -type=Level"
        assertEquals(listOf("generate", "-run", "^//go:generate stringer -type=Level\$", "level.go"), GoGenerateDirectiveIntention().arguments(directive, "level.go"))
        assertEquals(listOf("generate", "-run", "^//go:generate go run \\./gen\\.go\$", "a.go"), GoGenerateDirectiveIntention().arguments("//go:generate go run ./gen.go", "a.go"))
        assertEquals(listOf("generate", "level.go"), GoGenerateFileIntention().arguments(directive, "level.go"))
        assertEquals(listOf("generate", "."), GoGeneratePackageIntention().arguments(directive, "level.go"))
    }
}
