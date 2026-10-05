package io.github.golangsupport.ide.completion.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Optional re-ranking of Go completion candidates (docs/ML.md section 2). go-psi registers no
 * implementation; a host plugin or an optional `go-ml` module may contribute one through
 * `<extensions defaultExtensionNs="io.github.golangsupport"><completionRanker implementation="..."/>`.
 *
 * The completion contributor calls [rank] once per completion session and provider batch, on the
 * thread running completion (a background read action); implementations must be fast (budget:
 * < 1 ms per 100 candidates), must not load the AST of other files and must not throw. The first
 * registered ranker returning a non-null list wins. Its scores are consulted by
 * `GoCompletionWeigher` right after the expected-type match and before the deterministic scope
 * order (locals > parameters > package > imported > universe).
 */
interface GoCompletionRanker {
    /**
     * Scores for [candidates], in the same order and of the same size (higher is better), or
     * null to abstain (the deterministic order applies).
     */
    fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>?

    /**
     * A short grey text appended to the rows this ranker scored (so that a user sees which order is the model's), or
     * null for no mark. Read once per list, after a successful [rank].
     */
    val marker: String? get() = null

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<GoCompletionRanker> = ExtensionPointName.create("io.github.golangsupport.completionRanker")
    }
}

/** Where completion was invoked. [positionKind] is the name of `GoCompletionContext.Kind` (`EXPRESSION`, `SELECTOR`, ...). */
data class GoCompletionRankingContext(
    val file: PsiFile,
    val offset: Int,
    val positionKind: String,
    val prefix: String,
    /** The rendered expected type at the caret, null when there is none. */
    val expectedType: String?,
)

/** One candidate with the features the deterministic ranking uses. */
data class GoCompletionCandidate(
    val lookupString: String,
    /** Name of `GoCandidateKind` (`LOCAL`, `PARAMETER`, `FUNCTION`, `FIELD`, `KEYWORD`, ...). */
    val kind: String,
    /** Scope distance: 0 local, 1 parameter/receiver, 2 keyword/snippet, 3 package, 4 imported, 5 universe, 6 unimported. */
    val scopeLevel: Int,
    /** 2 identical to the expected type, 1 assignable, 0 no match or no expected type. */
    val expectedTypeMatch: Int,
    /** The declaration, when there is one; never use it to load other files' AST. */
    val element: PsiElement?,
)
