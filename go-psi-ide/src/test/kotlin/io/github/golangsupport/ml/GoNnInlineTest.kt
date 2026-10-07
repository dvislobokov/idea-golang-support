package io.github.golangsupport.ml

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The pure part of the grey text of the network: what it is given and what of its answer is shown (a fake engine, no platform). */
class GoNnInlineTest {
    private class FakeEngine(val answer: GoNnInline.Answer?) : GoNnEngine {
        var seen: GoNnInline.Context? = null
        override suspend fun complete(editor: Any, context: GoNnInline.Context): GoNnInline.Answer? { seen = context; return answer }
    }

    private fun shown(answer: GoNnInline.Answer?, text: String = "fmt.Prin)\n", offset: Int = 8): String? = runBlocking {
        val engine = FakeEngine(answer)
        val context = GoNnInline.context(text, offset, "main.go")
        GoNnInline.text(engine.complete(Any(), context))
    }

    @Test fun shownAnswerGivesItsText() = assertEquals("tln(\"hi\"", shown(GoNnInline.Answer("tln(\"hi\"", show = true, confProd = 0.93)))

    @Test fun hiddenOrEmptyAnswerGivesNothing() {
        assertNull(shown(GoNnInline.Answer("tln(x", show = false, confProd = 0.4)))
        assertNull(shown(GoNnInline.Answer("", show = true, confProd = 0.99)))
        assertNull(shown(null))
    }

    @Test fun suggestionIsEmptyWithoutText() {
        assertSame(InlineCompletionSuggestion.Empty, GoNnInline.suggestion(null))
        assertNotSame(InlineCompletionSuggestion.Empty, GoNnInline.suggestion("ln()"))
    }

    @Test fun contextIsBytesAroundTheCaret() {
        val text = "package main\n\nfunc main() {\n\tfmt.Prin(\"привет\")\n}\n"
        val caret = text.indexOf("(\"")
        val c = GoNnInline.context(text, caret, "cmd/app/main.go")
        assertArrayEquals(text.substring(0, caret).toByteArray(), c.before)
        assertArrayEquals(text.substring(caret).toByteArray(), c.after)
        assertArrayEquals("cmd/app/main.go".toByteArray(), c.path)
    }

    @Test fun contextKeepsTheLimits() {
        val line = "x := 1 // ё\n"
        val text = line.repeat(10_000) + "y" + "z".repeat(100) + "\n" + line.repeat(10_000)
        val caret = line.length * 10_000 + 1
        val c = GoNnInline.context(text, caret, "a.go")
        assertEquals(GoNnInline.PREFIX_BYTES, c.before.size)
        assertArrayEquals(text.substring(0, caret).toByteArray().let { it.copyOfRange(it.size - GoNnInline.PREFIX_BYTES, it.size) }, c.before)
        // the rest of the line in full, then SUFFIX_BYTES of what follows it
        assertEquals(100 + GoNnInline.SUFFIX_BYTES, c.after.size)
        assertEquals("z".repeat(100) + "\n" + line, String(c.after, 0, 101 + line.toByteArray().size))
    }

    @Test fun codeConfidenceCountsTheCodeOnly() {
        // `fmt.` → `Errorf("store: no items")`: the words of the message are unlikely, the code around them is not (the quotes are part of the guess)
        fun b(s: String) = s.toByteArray()
        fun conf(line: String, tokens: List<String>, lp: FloatArray, stop: Float) = GoNnInline.codeConfidence(b(line), tokens.map { b(it) }, lp, stop)
        assertEquals(Math.exp(-0.2), conf("		return fmt.", listOf("Errorf", "(\"", "store", ":", " no", " items", "\")"), floatArrayOf(-0.1f, -0.1f, -3f, -2f, -2f, -2f, -1f), -0.1f), 1e-6)
        // the caret inside a string: only the end of the line counts
        assertEquals(Math.exp(-0.1), conf("	return fmt.Errorf(\"no ", listOf("items", "\")"), floatArrayOf(-4f, -1f), -0.1f), 1e-6)
        // an escaped quote does not close the string; a raw string closes with the backtick; a comment is text to the end of the line
        assertEquals(Math.exp(-0.3), conf("	x := \"a\\\"", listOf(" b", "\"", " + y"), floatArrayOf(-4f, -1f, -0.3f), Float.NaN), 1e-6)
        assertEquals(Math.exp(-0.1), conf("	x := `a", listOf(" b", "`", " + y"), floatArrayOf(-4f, -1f, -0.1f), Float.NaN), 1e-6)
        assertEquals(1.0, conf("	x := 1 // the", listOf(" answer"), floatArrayOf(-4f), -5f), 1e-6)
        // nothing but code: the plain product, including the end of the line
        assertEquals(Math.exp(-0.6), conf("	return ", listOf("len", "(o.items)"), floatArrayOf(-0.2f, -0.3f), -0.1f), 1e-6)
        assertArrayEquals(b("\treturn fmt."), GoNnInline.lineBefore(b("func f() error {\n\treturn fmt.Err"), 3))
        assertArrayEquals(b("x"), GoNnInline.lineBefore(b("x"), 0))
    }

    @Test fun pathIsRelativeToTheProject() {
        assertEquals("internal/x/a.go", GoNnInline.relativePath("C:/work/proj/", "C:/work/proj/internal/x/a.go"))
        assertEquals("a.go", GoNnInline.relativePath("C:\\work\\proj", "C:/work/other/a.go"))
        assertEquals("a.go", GoNnInline.relativePath(null, "/tmp/a.go"))
    }
}
