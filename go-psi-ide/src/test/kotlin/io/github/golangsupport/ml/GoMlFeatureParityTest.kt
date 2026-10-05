package io.github.golangsupport.ml

import io.github.completionml.core.rank.FeatureSchema
import io.github.completionml.core.spi.ContextKind
import io.github.golangsupport.ide.completion.GoCompletionTestBase
import io.github.golangsupport.ide.completion.GoCompletionWeigher
import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext

/**
 * Training/serving parity of the Go language block (`ml/docs/ADAPTER.md` §4): the features the offline export computes
 * from the lookup elements of a headless completion must equal the features the IDE ranker computes from what the
 * `GoCompletionRanker` extension point receives during the same completion.
 */
class GoMlFeatureParityTest : GoCompletionTestBase() {

    private val source = """
        package main

        import "strings"

        type server struct{ name string }

        func (s *server) Start(port int) error { return nil }

        func run(count int, label string) string {
            total := count + 1
            srv := &server{name: label}
            _ = srv.Start(total)
            return strings.ToUpper(<caret>)
        }
    """

    fun testLanguageBlockIsIdenticalOnBothPaths() {
        // IDE path: capture what the extension point receives (one call per provider batch)
        val fromEp = LinkedHashMap<String, Pair<GoCompletionRankingContext, GoCompletionCandidate>>()
        val ranker = object : GoCompletionRanker {
            override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>? {
                for (c in candidates) fromEp.putIfAbsent(c.lookupString, context to c)
                return null
            }
        }
        GoCompletionRanker.EP_NAME.point.registerExtension(ranker, testRootDisposable)
        val items = complete(source) ?: error("a single candidate was inserted; the test needs a list")

        // export path: the same objects rebuilt from the lookup elements
        val infos = items.mapNotNull { e -> GoCompletionWeigher.infoOf(e)?.let { e to it } }
        val fromLookup = infos.map { (e, info) -> GoCompletionCandidate(e.lookupString, info.kind.name, info.level, info.expectedMatch, info.element) }
        assertTrue("expected the real candidates, got ${fromLookup.map { it.lookupString }}", fromLookup.map { it.lookupString }.containsAll(listOf("label", "count", "total")))
        assertEquals(fromLookup.map { it.lookupString }.toSet(), fromEp.keys)

        val caret = myFixture.editor.caretModel.offset
        val exportContext = GoCompletionRankingContext(myFixture.file, caret, "", "", null)
        val exportBlock = GoMlFeatures.languageBlock(exportContext, fromLookup)
        // the EP sees the candidates in batches; the block is list-relative, so compare it on the same list
        val epCandidates = fromLookup.map { fromEp.getValue(it.lookupString).second }
        val epContext = fromEp.values.first().first
        assertEquals(caret, epContext.offset)
        val epBlock = GoMlFeatures.languageBlock(GoCompletionRankingContext(epContext.file, epContext.offset, epContext.positionKind, epContext.prefix, epContext.expectedType), epCandidates)
        for (c in fromLookup.indices) {
            assertEquals("candidate ${fromLookup[c].lookupString}", exportBlock[c].toList(), epBlock[c].toList())
        }

        // sanity of the values themselves
        val names = GoMlFeatures.NAMES
        fun f(name: String, lookup: String): Float = exportBlock[fromLookup.indexOfFirst { it.lookupString == lookup }][names.indexOf(name)]
        assertEquals(1f, f("kind_param", "label")); assertEquals(1f, f("kind_local", "total"))
        assertEquals(2f, f("expected_type_match", "label"))          // string parameter of ToUpper
        assertEquals(1f, f("list_has_expected", "total"))
        assertEquals(1f, f("declared_in_file", "label")); assertTrue(f("decl_distance_log", "label") > 0f)
        assertEquals(0f, f("rule_rank_log", "label"))                // the rules put the string parameter first
        assertEquals(FeatureSchema.BASE.size + names.size, GoMlFeatures.schema.baseSize)
    }

    fun testContextKindFromTokens() {
        val tokens = GoMlLanguage.tokenizer.tokens("package p\nfunc f(a int) { x := a.b; return x }\n")
        val i = tokens.indexOfFirst { it.text == "b" }
        assertEquals(ContextKind.AFTER_DOT, GoMlFeatures.contextKind(tokens, i))
        assertEquals(ContextKind.OTHER, GoMlFeatures.contextKind(tokens, 0))
    }
}
