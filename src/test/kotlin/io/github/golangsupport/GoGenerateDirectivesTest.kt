package io.github.golangsupport

import io.github.golangsupport.build.GoGenerateDirectives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGenerateDirectivesTest {
    @Test
    fun directivesAsCmdGoReadsThem() {
        assertTrue(GoGenerateDirectives.isDirective("//go:generate stringer -type=Pill"))
        assertTrue(GoGenerateDirectives.isDirective("//go:generate\tgo run gen.go"))
        assertFalse("no space after the prefix", GoGenerateDirectives.isDirective("//go:generatefoo"))
        assertFalse(GoGenerateDirectives.isDirective("//go:generate"))
        assertFalse("not at the start of the line", GoGenerateDirectives.isDirective(" //go:generate x"))
        assertFalse(GoGenerateDirectives.isDirective("// go:generate x"))
        assertTrue(GoGenerateDirectives.hasDirectives("package p\n\n//go:generate x\n"))
        assertFalse(GoGenerateDirectives.hasDirectives("package p\n\t//go:generate x\n"))
    }

    @Test
    fun quoteMetaEscapesWhatGoEscapes() {
        assertEquals("""\\\.\+\*\?\(\)\|\[\]\{\}\^\$""", GoGenerateDirectives.quoteMeta("""\.+*?()|[]{}^$"""))
        assertEquals("plain-text_/ =:'\"", GoGenerateDirectives.quoteMeta("plain-text_/ =:'\""))
    }

    @Test
    fun runPatternMatchesExactlyThisDirective() {
        val line = "//go:generate mockgen -source=a.go -destination=mocks/a.go  "
        val pattern = GoGenerateDirectives.runPattern(line)
        assertEquals("""^//go:generate mockgen -source=a\.go -destination=mocks/a\.go$""", pattern)
        val regex = Regex(pattern)
        assertTrue(regex.matches(line.trim()))
        assertFalse(regex.matches("//go:generate mockgen -source=a.go -destination=mocks/a.go -x"))
        assertFalse("a dot is literal", regex.matches("//go:generate mockgen -source=aXgo -destination=mocks/a.go"))
        assertTrue(Regex(GoGenerateDirectives.runPattern("//go:generate sh -c \"echo (a|b) [x] $1\"")).matches("//go:generate sh -c \"echo (a|b) [x] $1\""))
    }

    @Test
    fun commandLines() {
        assertEquals(listOf("generate", "-run", "^//go:generate stringer -type=T$", "t.go"), GoGenerateDirectives.directiveArguments("//go:generate stringer -type=T", "t.go"))
        assertEquals(listOf("generate", "t.go"), GoGenerateDirectives.fileArguments("t.go"))
    }
}
