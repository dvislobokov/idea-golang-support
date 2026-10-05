package io.github.golangsupport.ide.annotator

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import io.github.golangsupport.ide.annotator.GoStringContentAnnotator.Companion.escapeRanges
import io.github.golangsupport.ide.annotator.GoStringContentAnnotator.Companion.tagRanges
import io.github.golangsupport.ide.annotator.GoStringContentAnnotator.Companion.verbRanges
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ranges [GoStringContentAnnotator] colours inside a literal, as `text KEY` pairs over the literal's source. */
class GoStringContentRangesTest {
    private fun show(source: String, ranges: List<Pair<TextRange, TextAttributesKey>>): List<String> =
        ranges.map { (range, key) -> "${range.substring(source)} ${key.externalName}" }

    @Test
    fun tagPartsOfARawTag() {
        val source = "`json:\"first_name\" arbitrary text`"
        assertEquals(listOf("json GO_TAG_KEY", ": GO_TAG_COLON", "\"first_name\" GO_TAG_VALUE", "arbitrary text GO_TAG_TEXT"), show(source, tagRanges(source)))
    }

    @Test
    fun tagPartsOfSeveralPairs() {
        val source = "`json:\"a,omitempty\" db:\"b\"`"
        assertEquals(
            listOf("json GO_TAG_KEY", ": GO_TAG_COLON", "\"a,omitempty\" GO_TAG_VALUE", "db GO_TAG_KEY", ": GO_TAG_COLON", "\"b\" GO_TAG_VALUE"),
            show(source, tagRanges(source)),
        )
    }

    @Test
    fun afterTextThatIsNoPairTheRestIsText() {
        // reflect.StructTag.Lookup stops at the first word that is not a pair: so does the colouring
        val source = "`json:\"a\" x db:\"b\"`"
        assertEquals(listOf("json GO_TAG_KEY", ": GO_TAG_COLON", "\"a\" GO_TAG_VALUE", "x db:\"b\" GO_TAG_TEXT"), show(source, tagRanges(source)))
    }

    @Test
    fun tagPartsOfAnInterpretedTagPointAtTheSource() {
        // "xml:\"name\"": the quotes of the value are escapes in the source
        val source = "\"xml:\\\"name\\\"\""
        assertEquals(listOf("xml GO_TAG_KEY", ": GO_TAG_COLON", "\\\"name\\\" GO_TAG_VALUE"), show(source, tagRanges(source)))
    }

    @Test
    fun aMalformedTagIsText() {
        val source = "`json:name`"
        assertEquals(listOf("json:name GO_TAG_TEXT"), show(source, tagRanges(source)))
    }

    @Test
    fun verbsWithFlagsWidthIndexAndPercent() {
        val source = "\"%d %-10s %[1]v %+v %6.2f %*d %% done\\n\""
        assertEquals(
            listOf("%d", "%-10s", "%[1]v", "%+v", "%6.2f", "%*d", "%%").map { "$it GO_FORMAT_VERB" },
            show(source, verbRanges(source)),
        )
    }

    @Test
    fun verbsOfARawFormatAndNoneInPlainText() {
        assertEquals(listOf("%q GO_FORMAT_VERB"), show("`%q`", verbRanges("`%q`")))
        assertEquals(emptyList<String>(), verbRanges("\"no verbs\""))
    }

    @Test
    fun validAndInvalidEscapes() {
        val u = "\\u" + "00e9"
        val source = "\"a\\n\\t\\x41\\101$u\\U0001F600\\\\\\\"|\\x4\\400\\q\\'\\uD800\""
        assertEquals(
            listOf(
                "\\n GO_VALID_STRING_ESCAPE", "\\t GO_VALID_STRING_ESCAPE", "\\x41 GO_VALID_STRING_ESCAPE", "\\101 GO_VALID_STRING_ESCAPE",
                "$u GO_VALID_STRING_ESCAPE", "\\U0001F600 GO_VALID_STRING_ESCAPE", "\\\\ GO_VALID_STRING_ESCAPE", "\\\" GO_VALID_STRING_ESCAPE",
                "\\x4 GO_INVALID_STRING_ESCAPE", "\\400 GO_INVALID_STRING_ESCAPE", "\\q GO_INVALID_STRING_ESCAPE", "\\' GO_INVALID_STRING_ESCAPE",
                "\\uD800 GO_INVALID_STRING_ESCAPE",
            ),
            show(source, escapeRanges(source)),
        )
    }

    @Test
    fun aRuneTakesItsOwnQuoteAndRawStringsHaveNoEscapes() {
        assertEquals(listOf("\\' GO_VALID_STRING_ESCAPE"), show("'\\''", escapeRanges("'\\''")))
        assertEquals(emptyList<String>(), escapeRanges("`a\\n`"))
    }
}
