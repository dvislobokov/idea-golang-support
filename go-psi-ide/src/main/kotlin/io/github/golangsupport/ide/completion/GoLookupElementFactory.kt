package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PsiFileImpl
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.documentation.GoDocSignature
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoSpecType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import javax.swing.Icon
import io.github.golangsupport.lang.psi.GoArrayOrSliceType as PsiArrayOrSliceType
import io.github.golangsupport.lang.psi.GoChannelType as PsiChannelType
import io.github.golangsupport.lang.psi.GoFunctionType as PsiFunctionType
import io.github.golangsupport.lang.psi.GoInterfaceType as PsiInterfaceType
import io.github.golangsupport.lang.psi.GoMapType as PsiMapType
import io.github.golangsupport.lang.psi.GoPointerType as PsiPointerType
import io.github.golangsupport.lang.psi.GoStructType as PsiStructType
import io.github.golangsupport.lang.psi.GoType as PsiType

/** Kinds of completion candidates (also the `kind` feature of [io.github.golangsupport.ide.completion.api.GoCompletionCandidate]). */
enum class GoCandidateKind {
    LOCAL, PARAMETER, VARIABLE, CONSTANT, FUNCTION, METHOD, FIELD, TYPE, TYPE_PARAMETER, PACKAGE,
    BUILTIN_FUNCTION, BUILTIN_TYPE, BUILTIN_CONSTANT, KEYWORD, SNIPPET, LABEL, IMPORT_PATH, STRUCT_KEY, PACKAGE_NAME,
    /** A value written for the expected type by smart completion: `T{}`, `&T{}`, `make(T)`, `func(...) {}`, `""`, `0`. */
    LITERAL,
}

/** Scope distance used by the deterministic ranking (lower is closer). */
object GoScopeLevel {
    const val LOCAL = 0
    const val PARAMETER = 1
    const val KEYWORD = 2
    const val PACKAGE = 3
    const val IMPORTED = 4
    const val UNIVERSE = 5
    const val UNIMPORTED = 6
}

/** Ranking features attached to every Go lookup element (read by [GoCompletionWeigher]). */
class GoLookupInfo(
    val name: String,
    val kind: GoCandidateKind,
    val level: Int,
    /** 2 identical to the expected type, 1 assignable, 0 otherwise. */
    val expectedMatch: Int,
    val element: PsiElement?,
) {
    /** Score of a registered [io.github.golangsupport.ide.completion.api.GoCompletionRanker], if any. */
    @Volatile
    var rankerScore: Double? = null

    companion object {
        val KEY: Key<GoLookupInfo> = Key.create("gopsi.completion.info")
    }
}

/**
 * A candidate before it becomes a lookup element: what to show, how to insert, how to rank.
 * [valueType] is the type of the value the candidate denotes (for functions: the signature),
 * used for the expected-type match and the tail/type texts; null when it is not known cheaply.
 */
class GoCandidate(
    val name: String,
    val kind: GoCandidateKind,
    val level: Int,
    val element: PsiElement? = null,
    val valueType: GoType? = null,
    var tailText: String? = null,
    var typeText: String? = null,
    /**
     * Rendered only when the lookup row is presented (see [GoLookupElementFactory.create]); they win over
     * [tailText]/[typeText]. Keeps type rendering off the completion hot path.
     */
    var tailSupplier: (() -> String?)? = null,
    var typeSupplier: (() -> String?)? = null,
    /** The import path to add on insertion (unimported packages and their members). */
    val importPath: String? = null,
    val icon: Icon? = null,
    val bold: Boolean = false,
    val insertHandler: InsertHandler<LookupElement>? = null,
    val lookupString: String = name,
    val presentableText: String? = null,
    /** More strings the prefix is matched against (`Name` for the chain `u.Profile.Name`, `Handler` for `api.Handler`). */
    val lookupStrings: Collection<String> = emptyList(),
)

/** Builds lookup elements: icon, tail and type texts, insert handlers, ranking info. */
object GoLookupElementFactory {

    fun create(candidate: GoCandidate, context: GoCompletionContext): LookupElement {
        val expected = if (context.isExpression || context.kind == GoCompletionContext.Kind.SELECTOR || context.kind == GoCompletionContext.Kind.STRUCT_KEY) {
            context.semantics.expectedType
        } else null
        val match = if (expected != null) expectedMatch(candidate, expected) else 0
        var builder = LookupElementBuilder.create(candidate.lookupString)
            .withIcon(candidate.icon ?: iconFor(candidate))
            .withBoldness(candidate.bold)
        candidate.element?.let { builder = builder.withPsiElement(it) }
        candidate.presentableText?.let { builder = builder.withPresentableText(it) }
        if (candidate.lookupStrings.isNotEmpty()) builder = builder.withLookupStrings(candidate.lookupStrings)
        val tailSupplier = candidate.tailSupplier
        val typeSupplier = candidate.typeSupplier
        if (tailSupplier != null || typeSupplier != null) {
            builder = builder.withRenderer(LazyRenderer(candidate, tailSupplier, typeSupplier))
        } else {
            candidate.tailText?.let { builder = builder.withTailText(it, true) }
            candidate.typeText?.let { builder = builder.withTypeText(it) }
        }
        val handler = candidate.insertHandler ?: defaultInsertHandler(candidate, context, expected)
        if (handler != null) builder = builder.withInsertHandler(handler)
        if (candidate.kind == GoCandidateKind.KEYWORD) builder = builder.withCaseSensitivity(true)
        builder.putUserData(GoLookupInfo.KEY, GoLookupInfo(candidate.name, candidate.kind, candidate.level, match, candidate.element))
        return builder
    }

    /** Number of lazy presentations rendered so far (test hook: completion itself must not render them). */
    internal val lazyRenderCount = java.util.concurrent.atomic.AtomicInteger()

    /** Renders icon, text, tail and type of a candidate with lazily computed texts when the row is shown. */
    private class LazyRenderer(
        private val candidate: GoCandidate,
        private val tail: (() -> String?)?,
        private val type: (() -> String?)?,
    ) : LookupElementRenderer<LookupElement>() {
        override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) {
            lazyRenderCount.incrementAndGet()
            presentation.itemText = candidate.presentableText ?: candidate.lookupString
            presentation.icon = candidate.icon ?: iconFor(candidate)
            presentation.isItemTextBold = candidate.bold
            val tailText = if (tail != null) tail() else candidate.tailText
            if (tailText != null) presentation.appendTailText(tailText, true)
            val typeText = if (type != null) type() else candidate.typeText
            if (typeText != null) presentation.typeText = typeText
        }
    }

    fun iconFor(candidate: GoCandidate): Icon? = when (candidate.kind) {
        GoCandidateKind.PACKAGE, GoCandidateKind.IMPORT_PATH, GoCandidateKind.PACKAGE_NAME -> AllIcons.Nodes.Package
        GoCandidateKind.KEYWORD -> null
        GoCandidateKind.SNIPPET, GoCandidateKind.LITERAL -> AllIcons.Nodes.Template
        GoCandidateKind.LABEL -> AllIcons.Nodes.Tag
        GoCandidateKind.BUILTIN_FUNCTION -> GoIdeIcons.FUNCTION
        GoCandidateKind.BUILTIN_TYPE -> GoIdeIcons.TYPE
        GoCandidateKind.BUILTIN_CONSTANT -> GoIdeIcons.CONSTANT
        GoCandidateKind.STRUCT_KEY -> GoIdeIcons.FIELD
        else -> candidate.element?.let(GoIdeIcons::forElement) ?: when (candidate.kind) {
            GoCandidateKind.FUNCTION -> GoIdeIcons.FUNCTION
            GoCandidateKind.METHOD -> GoIdeIcons.METHOD
            GoCandidateKind.FIELD -> GoIdeIcons.FIELD
            GoCandidateKind.TYPE, GoCandidateKind.TYPE_PARAMETER -> GoIdeIcons.TYPE
            GoCandidateKind.CONSTANT -> GoIdeIcons.CONSTANT
            else -> GoIdeIcons.VARIABLE
        }
    }

    /** 2: identical, 1: assignable (callables: by their single result), 0: no match. */
    fun expectedMatch(candidate: GoCandidate, expected: GoType): Int {
        val value = candidate.valueType ?: return 0
        if (value is GoUnknownType) return 0
        return when (candidate.kind) {
            GoCandidateKind.FUNCTION, GoCandidateKind.METHOD, GoCandidateKind.BUILTIN_FUNCTION -> {
                val signature = value as? GoSignatureType ?: return 0
                if (expected.underlying() is GoSignatureType && GoTypePredicates.assignable(signature, expected)) return 2
                if (signature.results.size != 1) return 0
                matchValue(signature.results[0].type, expected)
            }
            GoCandidateKind.TYPE, GoCandidateKind.BUILTIN_TYPE, GoCandidateKind.TYPE_PARAMETER, GoCandidateKind.PACKAGE,
            GoCandidateKind.KEYWORD, GoCandidateKind.SNIPPET, GoCandidateKind.LABEL, GoCandidateKind.IMPORT_PATH,
            GoCandidateKind.PACKAGE_NAME, GoCandidateKind.STRUCT_KEY -> 0
            else -> matchValue(value, expected)
        }
    }

    /**
     * The match of smart completion: as [expectedMatch], except that a function or method is matched by its first result whatever
     * the number of results (`f()` of `func f() (T, error)` fits a `T`).
     */
    fun smartMatch(candidate: GoCandidate, expected: GoType): Int {
        if (candidate.kind != GoCandidateKind.FUNCTION && candidate.kind != GoCandidateKind.METHOD) return expectedMatch(candidate, expected)
        val signature = candidate.valueType as? GoSignatureType ?: return 0
        if (expected.underlying() is GoSignatureType) return if (GoTypePredicates.assignable(signature, expected)) 2 else 0
        return signature.results.firstOrNull()?.let { matchValue(it.type, expected) } ?: 0
    }

    fun matchValue(value: GoType, expected: GoType): Int = when {
        value is GoUnknownType -> 0
        GoTypePredicates.identical(value, expected) -> 2
        value == GoBasicType.UNTYPED_NIL -> if (GoTypePredicates.assignable(value, expected)) 1 else 0
        GoTypePredicates.assignable(value, expected) -> if (GoTypePredicates.isUntyped(value)) 2 else 1
        else -> 0
    }

    private fun defaultInsertHandler(candidate: GoCandidate, context: GoCompletionContext, expected: GoType?): InsertHandler<LookupElement>? {
        val importPath = candidate.importPath
        val inExpression = context.isExpression || context.kind == GoCompletionContext.Kind.SELECTOR
        return when (candidate.kind) {
            GoCandidateKind.FUNCTION, GoCandidateKind.METHOD, GoCandidateKind.BUILTIN_FUNCTION -> {
                val signature = candidate.valueType as? GoSignatureType
                val asValue = expected != null && expected.underlying() is GoSignatureType
                if (!inExpression || asValue) importHandler(importPath) else callHandler(candidate.kind == GoCandidateKind.BUILTIN_FUNCTION && candidate.name in NO_ARG_BUILTINS || signature != null && signature.params.isEmpty(), importPath)
            }
            GoCandidateKind.PACKAGE -> packageHandler(importPath)
            GoCandidateKind.STRUCT_KEY -> structKeyHandler
            GoCandidateKind.TYPE -> if (candidate.element is GoTypeSpec && compositeLiteralPosition(context)) literalHandler(candidate.element, importPath) else importHandler(importPath)
            else -> importHandler(importPath)
        }
    }

    private val NO_ARG_BUILTINS = setOf("recover")

    /**
     * A type name typed where a value is expected (`x := Cir`, `return Cir`, `f(Cir`, `&Squ`, `json.Dec` in such a place): GoLand writes the
     * composite literal (probe 19). Not at a statement start, not as the type argument of `make`/`new`, not inside `f[...]` (an instantiation).
     */
    fun compositeLiteralPosition(context: GoCompletionContext): Boolean {
        val ref: PsiElement = when (context.kind) {
            GoCompletionContext.Kind.EXPRESSION -> context.reference ?: context.typeReference ?: return false
            GoCompletionContext.Kind.SELECTOR -> context.reference?.takeIf { !atStatementStart(it) } ?: return false
            else -> return false
        }
        return when (val parent = ref.parent) {
            is GoArgumentList -> ((parent.parent as? GoCallExpr)?.expression as? GoReferenceExpression)?.let { it.expression == null && it.identifier?.text in TYPE_ARGUMENT_BUILTINS } != true
            is GoIndexOrSliceExpr -> false
            else -> true
        }
    }

    private val TYPE_ARGUMENT_BUILTINS = setOf("make", "new")

    private fun atStatementStart(ref: PsiElement): Boolean {
        val list = ref.parent as? GoLeftHandExprList ?: return false
        return list.parent is GoSimpleStatement && list.parent.children.size == 1 && list.children.count { it is GoExpression } == 1
    }

    /**
     * `Name{<caret>}` for a struct, map, slice or array type (decided at insertion, so building the list reads no type declaration); other
     * types (`type MyInt int`, interfaces, generic types that need type arguments) stay a bare name. Nothing is added before `{`, `(`, `.` or `[`.
     */
    private fun literalHandler(spec: GoTypeSpec, importPath: String?): InsertHandler<LookupElement> = InsertHandler { ctx, _ ->
        val tail = ctx.tailOffset
        val chars = ctx.document.charsSequence
        val next = if (tail < chars.length) chars[tail] else ' '
        val decl = CompletionUtil.getOriginalOrSelf(spec)
        if (next !in "{(.[" && decl.isValid && decl.typeParameters == null && typeKind(decl) in COMPOSITE_KINDS) {
            ctx.document.insertString(tail, "{}")
            ctx.editor.caretModel.moveToOffset(tail + 1)
        }
        if (importPath != null) GoImportInserter.addImport(ctx, importPath)
    }

    private val COMPOSITE_KINDS = setOf("struct", "map", "slice")

    private fun importHandler(importPath: String?): InsertHandler<LookupElement>? =
        if (importPath == null) null else InsertHandler { ctx, _ -> GoImportInserter.addImport(ctx, importPath) }

    /** `name()` with the caret inside the parentheses, or after them when there are no parameters. */
    fun callHandler(noParameters: Boolean, importPath: String?): InsertHandler<LookupElement> = InsertHandler { ctx, _ ->
        val document = ctx.document
        val tail = ctx.tailOffset
        val chars = document.charsSequence
        if (tail < chars.length && chars[tail] == '(') {
            ctx.editor.caretModel.moveToOffset(tail + 1)
        } else {
            document.insertString(tail, "()")
            ctx.editor.caretModel.moveToOffset(tail + if (noParameters) 2 else 1)
        }
        if (importPath != null) GoImportInserter.addImport(ctx, importPath)
    }

    /** `pkg.` and the member popup; adds the import for unimported packages. */
    private fun packageHandler(importPath: String?): InsertHandler<LookupElement> = InsertHandler { ctx, _ ->
        val document = ctx.document
        val tail = ctx.tailOffset
        val chars = document.charsSequence
        if (tail < chars.length && chars[tail] == '.') {
            ctx.editor.caretModel.moveToOffset(tail + 1)
        } else {
            document.insertString(tail, ".")
            ctx.editor.caretModel.moveToOffset(tail + 1)
        }
        if (importPath != null) GoImportInserter.addImport(ctx, importPath)
        AutoPopupController.getInstance(ctx.project).scheduleAutoPopup(ctx.editor)
    }

    /** `Name: ` for struct literal keys (an existing `:` is reused). */
    private val structKeyHandler = InsertHandler<LookupElement> { ctx, _ ->
        val document = ctx.document
        val tail = ctx.tailOffset
        val chars = document.charsSequence
        var i = tail
        while (i < chars.length && (chars[i] == ' ' || chars[i] == '\t')) i++
        if (i < chars.length && chars[i] == ':') {
            ctx.editor.caretModel.moveToOffset(i + 1)
        } else {
            document.insertString(tail, ": ")
            ctx.editor.caretModel.moveToOffset(tail + 2)
        }
    }

    /** Inserts [suffix] after a keyword and puts the caret at [caretShift] inside it (default: end). */
    fun keywordHandler(suffix: String, caretShift: Int = suffix.length): InsertHandler<LookupElement>? {
        if (suffix.isEmpty()) return null
        return InsertHandler { ctx, _ ->
            val tail = ctx.tailOffset
            val chars = ctx.document.charsSequence
            val already = chars.length >= tail + suffix.length && chars.subSequence(tail, tail + suffix.length).toString() == suffix
            if (!already) {
                // A single space is reused when the keyword is followed by one already.
                if (suffix == " " && tail < chars.length && chars[tail] == ' ') {
                    ctx.editor.caretModel.moveToOffset(tail + 1)
                    return@InsertHandler
                }
                ctx.document.insertString(tail, suffix)
            }
            ctx.editor.caretModel.moveToOffset(tail + caretShift)
            if (suffix.endsWith("(") || suffix == "[" ) AutoPopupController.getInstance(ctx.project).autoPopupParameterInfo(ctx.editor, null)
        }
    }

    // --- texts ---

    /** `(a int, b string) error` for a signature (gopls style, `any` for the empty interface). */
    fun signatureTail(signature: GoSignatureType): String = GoDocSignature.renderType(signature).removePrefix("func")

    /** GoLand's tail of a function or method row: the type and value parameters only (`(s string, sep string)`); the results go to [resultText]. */
    fun paramsTail(signature: GoSignatureType): String =
        GoDocSignature.renderType(GoSignatureType(signature.params, emptyList(), signature.variadic, signature.typeParams)).removePrefix("func")

    /** GoLand's type column of a function or method row: `int`, `(string, error)`, `(err error)`; null without results. */
    fun resultText(signature: GoSignatureType): String? =
        if (signature.results.isEmpty()) null else GoDocSignature.renderType(GoSignatureType(emptyList(), signature.results, false)).removePrefix("func() ")

    /** GoLand's owner suffix of a member row: ` → Base`, ` → *Square`, ` → interface {...}`. */
    fun ownerTail(owner: String?): String = if (owner == null) "" else " → $owner"

    /** The tail of [base] (lazy or not) followed by [suffix]: the package path of a member of another package (`(v any) encoding/json`). */
    fun tailWith(base: GoCandidate, suffix: String): () -> String? {
        val tail = base.tailSupplier
        val text = base.tailText
        return { (tail?.invoke() ?: text).orEmpty() + suffix }
    }

    /** GoLand's tail of a member of another package: `(v any) encoding/json` for functions, just ` encoding/json` for types (no `struct`). */
    fun foreignTail(base: GoCandidate, importPath: String): () -> String? =
        if (base.kind == GoCandidateKind.TYPE) ({ " $importPath" }) else tailWith(base, " $importPath")

    fun typeText(type: GoType): String = GoDocSignature.renderType(type)

    /** `struct`, `interface` or `type` (aliases: `= T` is not shown); stub-safe. */
    fun typeKind(spec: GoTypeSpec): String {
        val t = spec.type
        val inner = if (t is GoSpecType) PsiTreeUtil.getStubChildOfType(t, PsiType::class.java) ?: t else t
        return when (inner) {
            is PsiStructType -> "struct"
            is PsiInterfaceType -> "interface"
            is PsiFunctionType -> "func"
            is PsiMapType -> "map"
            is PsiArrayOrSliceType -> "slice"
            is PsiChannelType -> "chan"
            is PsiPointerType -> "pointer"
            else -> "type"
        }
    }

    /** True when reading [element]'s AST is free (same file, or its file's AST is loaded already). */
    fun astAvailable(element: PsiElement, context: GoCompletionContext): Boolean {
        val file = element.containingFile ?: return false
        if (file === context.file || file === context.originalFile) return true
        return (file as? PsiFileImpl)?.treeElement != null
    }

    /** Commits the document of [ctx] so PSI-based insert handlers see the inserted text. */
    fun commit(ctx: InsertionContext) {
        PsiDocumentManager.getInstance(ctx.project).commitDocument(ctx.document)
    }
}
