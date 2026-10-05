package io.github.golangsupport

import io.github.golangsupport.build.GoPlayground
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** What Share / Run in Playground send and the link they make of the answer; no network. */
class GoPlaygroundTest {
    private val file = "package main\n\nfunc main() {\n\tprintln(\"привет\")\n}\n"

    @Test
    fun theSelectionWinsOverTheFile() {
        assertEquals("println(1)", GoPlayground.code(file, "println(1)"))
        assertEquals(file, GoPlayground.code(file, null))
        assertEquals(file, GoPlayground.code(file, "  \n"))
    }

    @Test
    fun theBodyIsTheSourceInUtf8() {
        assertArrayEquals(file.toByteArray(Charsets.UTF_8), GoPlayground.requestBody(file))
        assertEquals("https://play.golang.org/share", GoPlayground.SHARE_URL)
    }

    @Test
    fun theAnswerIsTheIdOfTheSnippet() {
        assertEquals("https://go.dev/play/p/Ab3_x-9Zq", GoPlayground.snippetUrl("Ab3_x-9Zq\n"))
        assertNull("an error page of a proxy is not an id", GoPlayground.snippetUrl("<html><body>Forbidden</body></html>"))
        assertNull(GoPlayground.snippetUrl(""))
    }

    @Test
    fun emptyAndTooBigCodeIsNotSent() {
        assertNotNull(GoPlayground.problem("  "))
        assertNotNull(GoPlayground.problem("x".repeat(GoPlayground.MAX_SIZE + 1)))
        assertNull(GoPlayground.problem(file))
    }
}
