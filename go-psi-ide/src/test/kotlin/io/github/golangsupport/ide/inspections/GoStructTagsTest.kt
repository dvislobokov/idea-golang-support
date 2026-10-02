package io.github.golangsupport.ide.inspections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure struct tag functions behind `GoStructTagInspection` (vet `validateStructTag` and `reflect.StructTag.Lookup`). */
class GoStructTagsTest {

    private fun error(tag: String) = GoStructTags.parse(tag).error

    @Test
    fun validTags() {
        assertNull(error(""))
        assertNull(error("json:\"name,omitempty\" xml:\"name\""))
        assertNull(error("json:\"a b\""))
        assertNull(error("  json:\"a\"  "))
        assertNull(error("json:\"\\u00e9\""))
    }

    @Test
    fun vetErrors() {
        assertEquals(GoStructTags.ERR_VALUE, error("json:a"))
        assertEquals(GoStructTags.ERR_VALUE, error("json: \"a\""))
        assertEquals(GoStructTags.ERR_VALUE, error("json:\"a"))
        assertEquals(GoStructTags.ERR_PAIR, error("json"))
        assertEquals(GoStructTags.ERR_KEY, error("\"json\":\"a\""))
        assertEquals(GoStructTags.ERR_SPACE, error("json:\"a\",xml:\"b\""))
        assertEquals(GoStructTags.ERR_VALUE_SPACE, error("json:\"a,omitempty \""))
        assertEquals(GoStructTags.ERR_VALUE_SPACE, error("xml:\" a\""))
        assertEquals(GoStructTags.ERR_VALUE, error("json:\"\\q\""))
    }

    @Test
    fun lookupLikeReflect() {
        val parsed = GoStructTags.parse("json:\"a\" json:\"b\" yaml:\"c\"")
        assertEquals("a", parsed.lookup("json"))
        assertEquals("c", parsed.lookup("yaml"))
        assertNull(parsed.lookup("xml"))
        assertEquals(3, parsed.pairs.size)
    }

    @Test
    fun repairedQuoting() {
        assertEquals("json:\"a\"", GoStructTags.repaired("json:a"))
        assertEquals("json:\"a,omitempty\" xml:\"b\"", GoStructTags.repaired("json:a,omitempty xml:b"))
        assertEquals("json:\"a\"", GoStructTags.repaired("json: \"a\""))
        assertEquals("json:\"a\"", GoStructTags.repaired("json:\"a"))
        assertEquals("json:\"a\" xml:\"b\"", GoStructTags.repaired("json:\"a\",xml:\"b\""))
        assertEquals("json:\"a\" xml:\"b\"", GoStructTags.repaired("json:\"a\"xml:\"b\""))
    }

    @Test
    fun noRepairWhenAmbiguousOrValid() {
        assertNull(GoStructTags.repaired("json:\"a\""))
        assertNull(GoStructTags.repaired("json"))
        assertNull(GoStructTags.repaired("json:\"a b"))
        assertNull(GoStructTags.repaired("json:'a'"))
        assertNull(GoStructTags.repaired("json:a\"b"))
    }

    @Test
    fun withoutPair() {
        val tag = "json:\"a\" xml:\"x\" json:\"b\""
        val parsed = GoStructTags.parse(tag)
        assertEquals("json:\"a\" xml:\"x\"", GoStructTags.without(tag, parsed.pairs[2]))
        assertEquals("xml:\"x\" json:\"b\"", GoStructTags.without(tag, parsed.pairs[0]))
    }

    @Test
    fun unquoteAndQuote() {
        assertEquals("a\"b\\c\n", GoStructTags.unquote("\"a\\\"b\\\\c\\n\""))
        assertEquals("A", GoStructTags.unquote("\"\\x41\""))
        assertEquals("A", GoStructTags.unquote("\"\\101\""))
        assertNull(GoStructTags.unquote("\"\\'\""))
        assertNull(GoStructTags.unquote("\"a"))
        assertEquals("\"json:\\\"a\\\"\"", GoStructTags.quote("json:\"a\""))
    }
}
