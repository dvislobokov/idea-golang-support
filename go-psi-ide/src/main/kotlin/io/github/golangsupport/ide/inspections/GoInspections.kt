package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.options.OptPane
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoDiagnostic

/** Undefined names, undefined or unexported members, unknown struct literal fields. Fix: add a missing import. */
class GoUnresolvedReferenceInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.UNRESOLVED

    override fun highlightType(d: GoDiagnostic) =
        if (d.code == "undefined" || d.code == "undefined-member") ProblemHighlightType.LIKE_UNKNOWN_SYMBOL else ProblemHighlightType.GENERIC_ERROR_OR_WARNING

    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> =
        if (d.code == "undefined") GoAddImportFix.forUndefined(file, d.range) else emptyList()
}

/** `"os" imported and not used`. Fixes: remove the import, optimize imports. */
class GoUnusedImportInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.UNUSED_IMPORT
    override fun highlightType(d: GoDiagnostic) = ProblemHighlightType.LIKE_UNUSED_SYMBOL
    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> = listOf(GoRemoveImportFix(), GoOptimizeImportsFix())
}

/**
 * `declared and not used: x`. Fixes: remove the declaration, `_ = expr`, or rename to `_`. A compile error in Go, so an error with the
 * red wave like GoLand's, not the grey "unused symbol" text (seen live: the grey was invisible in Darcula and read as "no highlighting").
 */
class GoUnusedVariableInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.UNUSED_VARIABLE
    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> = GoUnusedVariableFixes.forDefinition(element)
}

/** `label L declared and not used`. */
class GoUnusedLabelInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.UNUSED_LABEL
    override fun highlightType(d: GoDiagnostic) = ProblemHighlightType.LIKE_UNUSED_SYMBOL
}

/** Assignability, representability and conversion errors. Fixes: wrap the value in a conversion `T(x)`; add the methods a type lacks to implement an interface. */
class GoTypeMismatchInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.TYPE_MISMATCH
    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> =
        if (d.code == "assignability") listOfNotNull(GoWrapConversionFix.create(file, d, element), GoImplementMissingMethodsFix.create(file, d, element)) else emptyList()
}

/** Wrong number of arguments, return values or assigned values; multi-value and no-value misuse. */
class GoCallArityInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.ARITY
}

/** Redeclarations in a block, a signature or the package; `no new variables on left side of :=`. */
class GoDuplicateDeclarationInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.DUPLICATE
}

/**
 * Instantiation, inference and constraint errors. `cannot infer T` is on by default since 0.0.9:
 * the GOROOT check gate and the golang.org/x sample report no false `cannot infer` any more
 * (recursive generic calls are inferred on renamed type parameters). The option can turn it off.
 */
class GoGenericsInspection : GoDiagnosticsInspectionBase() {
    @JvmField
    var reportCannotInfer: Boolean = true

    override fun accepts(code: String) = code in GoDiagnosticClasses.GENERICS

    override fun isReported(d: GoDiagnostic): Boolean = if (d.code == "cannot-infer") reportCannotInfer else super.isReported(d)

    override fun getOptionsPane(): OptPane = OptPane.pane(
        OptPane.checkbox("reportCannotInfer", "Report 'cannot infer' errors"),
    )
}

/** `missing return` at the closing brace of a function with results whose body can fall off the end (spec "Terminating statements"). */
class GoMissingReturnInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code in GoDiagnosticClasses.MISSING_RETURN
}

/** Every other type-checker error (operators, indexing, literals, statements, builtins, ...). Fix of an impossible type assertion: add the missing methods. */
class GoCheckerInspection : GoDiagnosticsInspectionBase() {
    override fun accepts(code: String) = code !in GoDiagnosticClasses.CLAIMED

    override fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> =
        when (d.code) {
            "type-assertion" -> listOfNotNull(GoImplementMissingMethodsFix.create(file, d, element))
            "unused-value" -> listOfNotNull(io.github.golangsupport.ide.inspections.lint.GoAssignResultFix.forUnusedAppend(element))
            else -> emptyList()
        }
}

/** The inspection classes contributed by go-psi-ide (for tests and docs). */
object GoInspectionClasses {
    val ALL: List<Class<out GoDiagnosticsInspectionBase>> = listOf(
        GoUnresolvedReferenceInspection::class.java,
        GoUnusedImportInspection::class.java,
        GoUnusedVariableInspection::class.java,
        GoUnusedLabelInspection::class.java,
        GoTypeMismatchInspection::class.java,
        GoCallArityInspection::class.java,
        GoDuplicateDeclarationInspection::class.java,
        GoGenericsInspection::class.java,
        GoMissingReturnInspection::class.java,
        GoCheckerInspection::class.java,
    )
}
