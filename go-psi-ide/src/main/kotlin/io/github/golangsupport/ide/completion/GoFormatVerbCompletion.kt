package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoCompletionContext.Kind
import io.github.golangsupport.ide.inspections.printf.GoFormatString
import io.github.golangsupport.ide.inspections.printf.GoPrintKind
import io.github.golangsupport.ide.inspections.printf.GoPrintfCalls
import io.github.golangsupport.ide.inspections.printf.GoPrintfTypes
import io.github.golangsupport.ide.inspections.printf.GoStringValue
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Printf verbs inside the format string of a printf-like call ([GoPrintfCalls]): after `%` (and flags, width, precision, `[n]`)
 * the verbs `%v %+v %#v %T %d %s %q %x %t %f %.2f ...`, `%w` only in `Errorf`-like calls, ranked by the type of the argument
 * the directive will read. Purely textual position checks here; the call is resolved by the provider.
 */
object GoFormatVerbCompletion {
    /** A directive being typed at the end of [before] (the literal's source text after the opening quote, up to the caret). */
    private val PREFIX = Regex("%[#0+\\- ]*(\\[\\d+])?(\\*|\\d+)?(\\.(\\[\\d+])?(\\*|\\d+)?)?(\\[\\d+])?$")

    /** The directive prefix (`%`, `%-8`, `%.`) the caret ends, or null when the caret is not in an unfinished directive (`%%` is a percent sign). */
    fun directivePrefix(before: String): String? {
        val m = PREFIX.find(before) ?: return null
        // The directive's own `%` must not be the second half of `%%`: count the run of `%` it ends.
        var run = 0
        var i = m.range.first
        while (i >= 0 && before[i] == '%') { run++; i-- }
        return if (run % 2 == 1) m.value else null
    }

    /** The caret at [offset] is in an unfinished directive of a string literal that is a call argument (syntactic). */
    fun isDirectivePosition(leaf: PsiElement, offset: Int): Boolean {
        val literal = leaf.parent as? GoStringLiteral ?: return false
        if (literal.parent !is GoArgumentList) return false
        val inLeaf = offset - leaf.textRange.startOffset
        if (inLeaf < 1 || inLeaf > leaf.textLength) return false
        return directivePrefix(leaf.text.substring(1, inLeaf)) != null
    }

    /** One verb item: the text after the prefix, what it prints. */
    class Verb(val suffix: String, val description: String, val bareOnly: Boolean = false)

    val VERBS: List<Verb> = listOf(
        Verb("v", "value in default format"),
        Verb("+v", "value with struct field names", bareOnly = true),
        Verb("#v", "Go-syntax representation", bareOnly = true),
        Verb("T", "Go-syntax type of the value"),
        Verb("d", "base 10 integer"),
        Verb("s", "string or []byte"),
        Verb("q", "quoted string or character"),
        Verb("x", "base 16, lower-case"),
        Verb("X", "base 16, upper-case"),
        Verb("t", "true or false"),
        Verb("f", "decimal point, no exponent"),
        Verb(".2f", "two decimals", bareOnly = true),
        Verb("e", "scientific notation"),
        Verb("g", "%e or %f, whichever is shorter"),
        Verb("b", "base 2"),
        Verb("o", "base 8"),
        Verb("c", "character of the code point"),
        Verb("U", "Unicode format U+1234"),
        Verb("p", "pointer address"),
        Verb("w", "wrapped error"),
        Verb("%", "literal percent sign", bareOnly = true),
    )

    /** Verb suffixes that fit [type] best, best first (empty when the type is unknown). */
    fun preferred(type: GoType?, types: GoPrintfTypes, errorf: Boolean): List<String> {
        if (type == null || type is GoUnknownType) return emptyList()
        if (types.isError(type)) return if (errorf) listOf("w", "v", "s") else listOf("v", "s")
        if (types.isStringer(type)) return listOf("s", "v", "q")
        val u = type.underlying()
        if (u is GoSliceType && (u.elem.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8) return listOf("s", "x", "q")
        return when (u) {
            is GoBasicType -> when {
                u.kind == GoBasicKind.UNTYPED_RUNE -> listOf("c", "q", "d", "U")
                u.kind.isBoolean -> listOf("t", "v")
                u.kind.isInteger -> listOf("d", "x", "v", "c", "b", "o")
                u.kind.isFloat -> listOf("f", ".2f", "g", "e", "v")
                u.kind.isComplex -> listOf("g", "f", "v")
                u.kind.isString -> listOf("s", "q", "v", "x")
                else -> listOf("v")
            }
            is GoPointerType -> if (u.elem.underlying() is GoStructType) listOf("v", "+v", "p") else listOf("p", "v")
            is GoStructType -> listOf("v", "+v", "#v")
            is GoSliceType, is GoArrayType, is GoMapType -> listOf("v", "+v", "#v")
            else -> listOf("v", "T")
        }
    }
}

/** Completion of Printf verbs in the format string of a printf-like call (registered by [GoCompletionContributor]). Not dumb-aware: the call is resolved. */
internal class GoFormatVerbProvider : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(parameters: CompletionParameters, processing: ProcessingContext, result: CompletionResultSet) {
        val context = GoCompletionContext.of(parameters) ?: return
        if (context.kind != Kind.STRING_ARGUMENT) return
        val leaf = context.leaf
        val literal = leaf.parent as? GoStringLiteral ?: return
        val call = literal.parent?.parent as? GoCallExpr ?: return
        val inLeaf = parameters.offset - leaf.textRange.startOffset
        if (inLeaf < 1 || inLeaf > leaf.textLength) return
        val before = leaf.text.substring(1, inLeaf)
        val prefix = GoFormatVerbCompletion.directivePrefix(before) ?: return
        val printf = GoPrintfCalls.of(call)?.takeIf { it.isPrintf } ?: return
        if (printf.format !== literal) return
        val errorf = printf.kind == GoPrintKind.ERRORF
        val service = GoSemanticService.getInstance(call.project)
        val types = GoPrintfTypes(service)
        val preferred = GoFormatVerbCompletion.preferred(argumentType(printf.values, before, leaf.text[0], service), types, errorf)
        val bare = prefix == "%"
        val elements = ArrayList<LookupElement>()
        for (verb in GoFormatVerbCompletion.VERBS) {
            if (verb.bareOnly && !bare) continue
            if (verb.suffix == "w" && !errorf) continue
            val rank = preferred.indexOf(verb.suffix)
            val item = LookupElementBuilder.create(prefix + verb.suffix).withTailText("  " + verb.description, true).withCaseSensitivity(true)
            elements += PrioritizedLookupElement.withPriority(item, if (rank >= 0) 100.0 - rank else if (verb.suffix == "v") 50.0 else 0.0)
        }
        result.withPrefixMatcher(PlainPrefixMatcher(prefix)).addAllElements(elements)
        result.stopHere()
    }

    /** The type of the argument the directive at the caret reads: the directives before it, then its own `*`/`[n]`. */
    private fun argumentType(values: List<io.github.golangsupport.lang.psi.GoExpression>, before: String, quote: Char, service: GoSemanticService): GoType? {
        val value = GoStringValue.decode(quote + before)?.value ?: return null
        val parsed = GoFormatString.parse(value + "v")
        if (parsed.error != null) return null
        val index = parsed.directives.lastOrNull()?.verbArg ?: return null
        return values.getOrNull(index)?.let(service::typeOf)
    }
}

/** Opens the completion popup when `%` is typed in a string literal argument (the provider keeps it to printf-like calls). */
class GoFormatVerbTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != '%' || file !is GoFile) return Result.CONTINUE
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, project)) return Result.CONTINUE
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset) ?: return Result.CONTINUE
        if (!GoTokenSets.STRING_LITERALS.contains(leaf.node.elementType)) return Result.CONTINUE
        val literal = leaf.parent as? GoStringLiteral ?: return Result.CONTINUE
        if (literal.parent !is GoArgumentList) return Result.CONTINUE
        val inLeaf = offset - leaf.textRange.startOffset
        if (inLeaf < 1 || inLeaf >= leaf.textLength) return Result.CONTINUE
        if (GoFormatVerbCompletion.directivePrefix(leaf.text.substring(1, inLeaf) + "%") == null) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.STOP
    }
}
