package io.github.golangsupport.ide.inspections.printf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The format parser and the literal decoder (no platform). */
class GoFormatStringTest {

    private fun directives(format: String): List<String> = GoFormatString.parse(format).directives.map { "${it.text}:${it.argNums}" }

    @Test
    fun plainVerbsConsumeArgumentsInOrder() {
        assertEquals(listOf("%d:[0]", "%s:[1]", "%v:[2]"), directives("a %d b %s c %v"))
        assertEquals(3, GoFormatString.parse("a %d b %s c %v").argsNeeded)
    }

    @Test
    fun percentTakesNoArgument() {
        assertEquals(listOf("%%:[]", "%d:[0]"), directives("100%% of %d"))
        assertEquals(1, GoFormatString.parse("100%% of %d").argsNeeded)
    }

    @Test
    fun flagsWidthPrecision() {
        val d = GoFormatString.parse("%-08.3f").directives.single()
        assertEquals('f', d.verb)
        assertEquals("-0.", d.flags)
        assertEquals(listOf(0), d.argNums)
        assertEquals(6, d.verbOffset)
    }

    @Test
    fun starWidthAndPrecisionConsumeIntegers() {
        val d = GoFormatString.parse("%*.*f").directives.single()
        assertEquals(listOf(0, 1), d.starArgs)
        assertEquals(2, d.verbArg)
        assertEquals(3, GoFormatString.parse("%*.*f").argsNeeded)
    }

    @Test
    fun argumentIndexes() {
        assertEquals(listOf("%[2]d:[1]", "%[1]s:[0]"), directives("%[2]d %[1]s"))
        // After an index the position continues from it.
        assertEquals(listOf("%[2]d:[1]", "%d:[2]"), directives("%[2]d %d"))
        // `%[1]d` twice reuses the argument.
        assertEquals(1, GoFormatString.parse("%[1]d %[1]x").argsNeeded)
        assertEquals(true, GoFormatString.parse("%[1]d").anyIndex)
        // An index before `*` is the width's argument; the verb takes the next one.
        assertEquals(listOf("%[3]*d:[2, 3]"), directives("%[3]*d"))
        // `%[3]*.[2]*[1]f`: width 3, precision 2, value 1.
        assertEquals(listOf("%[3]*.[2]*[1]f:[2, 1, 0]"), directives("%[3]*.[2]*[1]f"))
    }

    @Test
    fun errors() {
        val missing = GoFormatString.parse("x %d %")
        assertEquals(listOf("%d:[0]"), missing.directives.map { "${it.text}:${it.argNums}" })
        assertEquals(GoFormatString.Error.Kind.MISSING_VERB, missing.error!!.kind)
        assertEquals("%", missing.error!!.directive)
        assertEquals(GoFormatString.Error.Kind.MISSING_VERB, GoFormatString.parse("%-5").error!!.kind)
        val bad = GoFormatString.parse("%[0]d")
        assertEquals(GoFormatString.Error.Kind.BAD_INDEX, bad.error!!.kind)
        assertEquals("0", bad.error!!.index)
        assertEquals("x", GoFormatString.parse("%[x]d").error!!.index)
        assertEquals(GoFormatString.Error.Kind.MISSING_BRACKET, GoFormatString.parse("%[1d").error!!.kind)
        assertNull(GoFormatString.parse("plain").error)
    }

    @Test
    fun unknownVerbIsParsedAndLeftToTheChecker() {
        val d = GoFormatString.parse("%z %!").directives
        assertEquals(listOf('z', '!'), d.map { it.verb })
        assertNull(GoPrintfVerbs.of('z'))
    }

    @Test
    fun interpretedLiteralOffsets() {
        val v = GoStringValue.decode("\"a\\t%d\\n\"")!!
        assertEquals("a\t%d\n", v.value)
        val d = GoFormatString.parse(v.value).directives.single()
        // `%d` is at source offsets 4..6 (after `"a\t`).
        assertEquals(4 until 6, v.sourceRange(d.start, d.end))
        // The `\n` escape spans two source chars.
        assertEquals(6 until 8, v.sourceRange(4, 5))
        assertEquals(false, v.isPlain(4))
        assertEquals(8, v.closingQuote)
    }

    @Test
    fun escapesOfEveryKind() {
        val v = GoStringValue.decode("\"\\x25d \\u00e9 \\101 \\\" \\\\\"")!!
        assertEquals("%d é A \" \\", v.value)
        // `\x25` is a `%`: the directive starts at the escape.
        val d = GoFormatString.parse(v.value).directives.single()
        assertEquals(1 until 6, v.sourceRange(d.start, d.end))
        assertEquals(false, v.isPlain(0))
        assertEquals(true, v.isPlain(1))
    }

    @Test
    fun rawLiteralKeepsBackslashesAndDropsCarriageReturns() {
        val v = GoStringValue.decode("`a\\n\r%s`")!!
        assertEquals("a\\n%s", v.value)
        val d = GoFormatString.parse(v.value).directives.single()
        assertEquals(5 until 7, v.sourceRange(d.start, d.end))
    }

    @Test
    fun constantValueHasNoRanges() {
        val v = GoStringValue.ofConstant("%d")
        assertNull(v.sourceRange(0, 2))
        assertNull(v.closingQuote)
        assertNotNull(GoFormatString.parse(v.value).directives.singleOrNull())
    }

    @Test
    fun possiblePrintfDirectiveInPrintArguments() {
        assertEquals(0..1, GoPrintfVerbs.possibleDirective("%d items"))
        assertEquals(2..5, GoPrintfVerbs.possibleDirective("a %-5s"))
        // A trailing `%` and URL escapes are not directives.
        assertNull(GoPrintfVerbs.possibleDirective("100%"))
        assertNull(GoPrintfVerbs.possibleDirective("a%20b"))
        assertNull(GoPrintfVerbs.possibleDirective("50% off"))
    }

    @Test
    fun flagsTable() {
        assertEquals('#', GoPrintfVerbs.unsupportedFlag(GoPrintfVerbs.of('s')!!, "#"))
        assertNull(GoPrintfVerbs.unsupportedFlag(GoPrintfVerbs.of('x')!!, "#0"))
        assertEquals('.', GoPrintfVerbs.unsupportedFlag(GoPrintfVerbs.of('t')!!, "."))
        assertEquals("1 arg", GoPrintfVerbs.count(1, "arg"))
        assertEquals("0 args", GoPrintfVerbs.count(0, "arg"))
    }
}
