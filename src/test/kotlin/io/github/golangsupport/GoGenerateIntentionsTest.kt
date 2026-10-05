package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.build.GoGenerateIntention
import io.github.golangsupport.build.GoRunGenerateOnCommentIntention
import io.github.golangsupport.build.GoRunGenerateOnFileIntention
import io.github.golangsupport.build.GoRunGenerateOnPackageIntention

/** Alt+Enter Run go generate on comment / file / package (GoLand parity G4): offered on directive lines only, and the `go` arguments. No `go` runs. */
class GoGenerateIntentionsTest : BasePlatformTestCase() {
    private val names = listOf("Run go generate on comment", "Run go generate on file", "Run go generate on package")

    private val source = """
        package p

        //go:generate stringer -type=Level
        type Level int

        // go:generate not a directive
        func f() {
        	//go:generate indented: go generate skips it
        }
    """.trimIndent() + "\n"

    private fun offeredAt(needle: String, delta: Int = 0): List<String> {
        val at = source.indexOf(needle).also { assertTrue(needle, it >= 0) } + delta
        myFixture.configureByText("level.go", source.substring(0, at) + "<caret>" + source.substring(at))
        return myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Run go generate") }
    }

    fun testOfferedOnTheDirectiveLine() {
        assertEquals(names, offeredAt("//go:generate stringer"))
        assertEquals(names, offeredAt("-type=Level"))
        assertEquals(names, offeredAt("Level\ntype", 5))
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
    }

    fun testArguments() {
        val directive = "//go:generate stringer -type=Level"
        assertEquals(listOf("generate", "-run", "^//go:generate stringer -type=Level\$", "level.go"), GoRunGenerateOnCommentIntention().arguments(directive, "level.go"))
        assertEquals(listOf("generate", "-run", "^//go:generate go run \\./gen\\.go\$", "a.go"), GoRunGenerateOnCommentIntention().arguments("//go:generate go run ./gen.go", "a.go"))
        assertEquals(listOf("generate", "level.go"), GoRunGenerateOnFileIntention().arguments(directive, "level.go"))
        assertEquals(listOf("generate", "."), GoRunGenerateOnPackageIntention().arguments(directive, "level.go"))
    }
}
