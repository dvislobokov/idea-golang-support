package io.github.golangsupport.ide.startup

import org.jetbrains.annotations.ApiStatus

/**
 * Detects plugins that register extensions for `language="go"`. The id of go-psi's language is `"Go"`
 * (case-sensitive); extensions keyed by `"go"` are silently never found. The platform-facing part (reading the
 * language-keyed extension points) lives in [GoLanguageIdCheckActivity]; this object is the pure logic.
 */
@ApiStatus.Internal
object GoLanguageIdCheck {
    /** The wrong spelling of the language id. */
    const val WRONG_ID: String = "go"

    /** Language-keyed extension points that Go plugins typically use; unknown ones are skipped. */
    val EXTENSION_POINTS: List<String> = listOf(
        "com.intellij.lang.parserDefinition",
        "com.intellij.lang.syntaxHighlighterFactory",
        "com.intellij.annotator",
        "com.intellij.localInspection",
        "com.intellij.completion.contributor",
        "com.intellij.lang.documentationProvider",
        "com.intellij.lang.formatter",
        "com.intellij.lang.foldingBuilder",
        "com.intellij.lang.findUsagesProvider",
        "com.intellij.lang.refactoringSupport",
        "com.intellij.lang.psiStructureViewFactory",
        "com.intellij.lang.commenter",
        "com.intellij.lang.braceMatcher",
        "com.intellij.lang.quoteHandler",
        "com.intellij.lang.namesValidator",
        "com.intellij.lang.inspectionSuppressor",
        "com.intellij.codeInsight.lineMarkerProvider",
        "com.intellij.codeInsight.parameterInfo",
    )

    /** True if [languageKey] is the lowercase spelling `go` (the correct id is `Go`, which does not match). */
    fun isWrongId(languageKey: String?): Boolean = languageKey == WRONG_ID

    /**
     * The distinct names of the plugins among [registrations] ((plugin name, language key) pairs, one per
     * extension) that registered an extension under the wrong id, in first-seen order.
     */
    fun offenders(registrations: List<Pair<String, String?>>): List<String> =
        registrations.filter { isWrongId(it.second) }.map { it.first }.distinct()
}
