package io.github.golangsupport.ml

import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        GoNnInline.text(engine.complete(Any(), context), context.after)
    }

    @Test fun whatTheLineAlreadyHasAfterTheCaretIsNotRepeated() {
        // `Validate() (int⟨⟩) {`: the model writes `, error) {` to the end of the line, the `) {` is there (seen live, accepted as `(int, error) {) {`)
        val line = "func (o *Order) Validate() (int) {\n"
        assertEquals(", error", shown(GoNnInline.Answer(", error) {", show = true, confProd = 0.86), text = line, offset = line.indexOf(") {")))
        assertEquals(", error", GoNnInline.trimOverlap(", error) {", ") {"))
        assertEquals("o.items", GoNnInline.trimOverlap("o.items)", ")\n}"))     // the engine's case: a closer
        assertEquals("f(x)", GoNnInline.trimOverlap("f(x)", ""))
        assertEquals("a)", GoNnInline.trimOverlap("a)", "b"))
        assertEquals("", GoNnInline.trimOverlap(") {", ") {"))
        assertNull(shown(GoNnInline.Answer(") {", show = true, confProd = 0.9), text = "f() {\n", offset = 2))
        assertEquals(") {", GoNnInline.restOfLine(") {\n}\n".toByteArray()))
        assertEquals("", GoNnInline.restOfLine("\n}".toByteArray()))
    }

    @Test fun whatRepeatsTheLineIsDropped() {
        fun b(s: String) = s.toByteArray()
        // the suggestion is exactly what follows the caret on the line: nothing (the overlap trim leaves nothing either)
        assertNull(GoNnInline.text(GoNnInline.Answer("items)", show = true, confProd = 0.9), b("items)\n}\n")))
        assertEquals("o.", GoNnInline.text(GoNnInline.Answer("o.items)", show = true, confProd = 0.9), b("items)\n}\n")))
        // the line would copy the previous one: `a.Name = b.Name` twice (the model repeats the line above)
        assertTrue(GoNnInline.repeatsPreviousLine(b("func f() {\n\ta.Name = b.Name\n\ta."), "Name = b.Name"))
        assertTrue(GoNnInline.repeatsPreviousLine(b("\ta.Name = b.Name\n\t"), "a.Name = b.Name"))
        assertNull(GoNnInline.text(GoNnInline.Answer("Name = b.Name", show = true, confProd = 0.9), b("\n}\n"), b("func f() {\n\ta.Name = b.Name\n\ta.")))
        // a different line, a longer or shorter one, a differing indentation, an empty previous line: shown
        assertFalse(GoNnInline.repeatsPreviousLine(b("\ta.Name = b.Name\n\ta."), "Age = b.Age"))
        assertFalse(GoNnInline.repeatsPreviousLine(b("\ta.Name = b.Name\n\ta."), "Name = b.Name2"))
        assertFalse(GoNnInline.repeatsPreviousLine(b("\ta.Name = b.Name\n\t\ta."), "Name = b.Name"))
        assertFalse(GoNnInline.repeatsPreviousLine(b("\n\ta."), "Name"))
        assertFalse(GoNnInline.repeatsPreviousLine(b("\ta."), "Name"))
        assertFalse(GoNnInline.repeatsPreviousLine(b(""), "x"))
        assertEquals("Age = b.Age", GoNnInline.text(GoNnInline.Answer("Age = b.Age", show = true, confProd = 0.9), b("\n}\n"), b("\ta.Name = b.Name\n\ta.")))
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

    @Test fun certainStartOfAnUncertainLineIsShown() {
        fun b(s: String) = s.toByteArray()
        fun lp(vararg p: Double) = FloatArray(p.size) { Math.log(p[it]).toFloat() }
        fun prefix(tokens: List<String>, probs: FloatArray, typed: Int, gate: Double = 0.7) = GoNnInline.certainPrefix(tokens.map(::b), probs, typed, gate)?.let { String(it) }
        // `if le` → ` len(o.items) == 0 {`: every token ≥ 0.96 but ` ==` 0.53 (seen live); typed `le` is inside the first token
        val tokens = listOf(" len", "(", "o", ".", "items", ")", " ==", " 0", " {")
        val probs = lp(1.0, 1.0, 1.0, 1.0, 0.97, 1.0, 0.53, 0.98, 0.96)
        assertEquals("n(o.items)", prefix(tokens, probs, typed = 3))
        assertEquals("en(o.items)", prefix(tokens, probs, typed = 2))
        // nothing certain, or only the typed part, or punctuation: nothing
        assertNull(prefix(tokens, lp(0.5, 1.0, 1.0, 1.0, 0.97, 1.0, 0.53, 0.98, 0.96), typed = 3))
        assertNull(prefix(listOf(" len", " =="), lp(1.0, 0.5), typed = 4))
        assertNull(prefix(listOf(")", " x"), lp(1.0, 0.5), typed = 0))
        // a fresh line: a lone `if` is no suggestion, `return 0` is one (`return 0,` loses its comma; seen in the log)
        assertNull(prefix(listOf("if", " len", "("), lp(0.9, 0.2, 1.0), typed = 0, gate = 0.25))
        assertEquals("return 0", prefix(listOf("return", " 0", ",", " ErrEmpty"), lp(0.8, 0.9, 0.9, 0.3), typed = 0, gate = 0.25))
        // `for` + ` _, item := range o.items {` with `item` unsure: ` _,` is no suggestion (seen live: Tab inserted ` _,`)
        assertNull(prefix(listOf(" _", ",", " item", " :=", " range"), lp(0.95, 0.95, 0.4, 1.0, 1.0), typed = 0))
        // an open call is not finished: the longest finished cut before it; never cut inside an identifier (`o.Curr` + `ency`), `o.` alone is too little
        assertEquals("mt.Errorf", prefix(listOf(" fmt", ".", "Errorf", "(", "\"x\""), lp(1.0, 1.0, 1.0, 1.0, 0.1), typed = 2))   // typed ` f`: the healed remainder starts at the pre-token boundary, with its space
        assertEquals("mt.Errorf", prefix(listOf(" fmt", ".", "Errorf", "(\"", "x"), lp(1.0, 1.0, 1.0, 0.5, 0.1), typed = 2))
        assertNull(prefix(listOf(" o", ".", "Curr", "ency", " =="), lp(1.0, 1.0, 1.0, 0.5, 1.0), typed = 1))
        assertEquals("o.Count()", prefix(listOf(" o", ".", "Count", "()", " =="), lp(1.0, 1.0, 1.0, 1.0, 0.5), typed = 1))
    }

    @Test fun blankLineIsIndentationOnly() {
        assertTrue(GoNnInline.blankLine("func f() {\n\t".toByteArray()))
        assertTrue(GoNnInline.blankLine("".toByteArray()))
        assertTrue(GoNnInline.blankLine("x\n".toByteArray()))
        assertFalse(GoNnInline.blankLine("func f() {\n\tr".toByteArray()))
        assertFalse(GoNnInline.blankLine("\treturn ".toByteArray()))
    }

    @Test fun gateIsLowerAfterADotAndOnABlankLine() {
        fun g(before: String) = GoNnInline.gate(before.toByteArray(), 0.7, 0.5, 0.25)
        assertTrue(GoNnInline.afterDot("\treturn fmt.".toByteArray()))
        assertFalse(GoNnInline.afterDot("\treturn fmt.Er".toByteArray()))
        assertFalse(GoNnInline.afterDot("".toByteArray()))
        assertEquals(0.5, g("\treturn fmt."), 0.0)
        assertEquals(0.7, g("\treturn fmt.Er"), 0.0)
        assertEquals(0.5, g("\tx := 1."), 0.0)   // a number's dot counts too: the measurement did not tell them apart
        assertEquals(0.25, g("func f() {\n\t"), 0.0)
    }

    @Test fun pathIsRelativeToTheProject() {
        assertEquals("internal/x/a.go", GoNnInline.relativePath("C:/work/proj/", "C:/work/proj/internal/x/a.go"))
        assertEquals("a.go", GoNnInline.relativePath("C:\\work\\proj", "C:/work/other/a.go"))
        assertEquals("a.go", GoNnInline.relativePath(null, "/tmp/a.go"))
    }
}
