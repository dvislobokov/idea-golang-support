package io.github.golangsupport.ide.injection

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoStringLiteral
import org.intellij.lang.regexp.RegExpLanguage

/**
 * Injects the platform's RegExp into the pattern argument (the first one) of `regexp.Compile`, `MustCompile`, `CompilePOSIX`,
 * `MustCompilePOSIX`, `MatchString`, `Match` and `MatchReader` when it is a string literal. The injection depends on resolve (the
 * callee must be the `regexp` package, not any `MustCompile`), so it is gated like the other resolve-based highlighting,
 * [GoIdeFeature.SEMANTIC_COLORS]. The dialect (RE2) is set by [GoRegExpLanguageHost] and [GoRegExpCapabilities].
 */
class GoRegExpInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(GoStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = context as? GoStringLiteral ?: return
        val host = literal as? PsiLanguageInjectionHost ?: return
        if (!host.isValidHost) return
        val call = GoInjectionTargets.callOfArgument(literal, 0) ?: return
        if (GoInjectionTargets.calleeName(call) !in FUNCTIONS) return
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.SEMANTIC_COLORS, literal.project)) return
        val callee = GoInjectionTargets.calleeOf(call) ?: return
        if (callee.pkg != "regexp" || callee.name !in FUNCTIONS) return
        registrar.startInjecting(RegExpLanguage.INSTANCE).addPlace(null, null, host, GoInjectionTargets.contentRange(host)).doneInjecting()
    }

    companion object {
        val FUNCTIONS = setOf("Compile", "MustCompile", "CompilePOSIX", "MustCompilePOSIX", "MatchString", "Match", "MatchReader")
    }
}
