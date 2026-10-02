package io.github.golangsupport.semantic.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoType

/**
 * The semantic API of go-psi for IDE features and host plugins: expression types, declaration
 * types, resolution, member lookup and type rendering. All methods are read-action safe, cached
 * on the Go modification trackers, and never throw for malformed code (unknown types instead).
 */
interface GoSemanticService {
    /** The type of [expr]; `GoUnknownType` when it cannot be determined. */
    fun typeOf(expr: GoExpression): GoType

    /** The type of a declaration used as a value (variable, constant, function, field, parameter) or the type it names. */
    fun declarationType(declaration: GoNamedElement): GoType

    /** The constant value of a constant expression, or null. */
    fun constantValue(expr: GoExpression): GoConstant?

    /** All targets of a value reference (`x`, `pkg.X`, `v.f`); several only for ambiguous or duplicated declarations. */
    fun resolve(reference: GoReferenceExpression): List<PsiElement>

    /** The declaration a type reference names. */
    fun resolve(reference: GoTypeReferenceExpression): PsiElement?

    /** The package of [file] as seen by the project model, or null for files outside any package. */
    fun packageOf(file: GoFile): GoPackage?

    /** The method set of [type] (promoted methods included; for `T` only value-receiver methods). */
    fun methodsOf(type: GoType): List<GoMethod>

    /** Whether [type] implements [iface] (method sets, and type terms for constraints). */
    fun implements(type: GoType, iface: GoInterfaceType): Boolean

    /** Field or method [name] on [type], following Go's selector rules. */
    fun lookupFieldOrMethod(type: GoType, name: String, fromFile: GoFile? = null): GoLookup.Selection?

    /** Java-friendly overload of [lookupFieldOrMethod] without a requesting file. */
    fun lookupFieldOrMethod(type: GoType, name: String): GoLookup.Selection? = lookupFieldOrMethod(type, name, null)

    /** A gopls-like rendering of [type]. */
    fun render(type: GoType, qualified: Boolean = false): String

    /** Java-friendly overload of [render] with unqualified names. */
    fun render(type: GoType): String = render(type, false)

    /**
     * Type-checks [file] and returns go/types-style diagnostics (undefined names, unused imports
     * and variables, assignability, call arity, operators, constraints, ...). Conservative: a
     * check fires only when every involved type is known.
     */
    fun check(file: GoFile): List<GoDiagnostic>

    /** The signature a call invokes, with inferred type arguments substituted; null for builtins and conversions. */
    fun calleeSignature(call: io.github.golangsupport.lang.psi.GoCallExpr): io.github.golangsupport.semantic.types.GoSignatureType?

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GoSemanticService = project.service()
    }
}
