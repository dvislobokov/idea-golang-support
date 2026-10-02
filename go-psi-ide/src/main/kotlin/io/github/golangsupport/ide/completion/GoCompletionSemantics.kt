package io.github.golangsupport.ide.completion

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.infer.GoExpectedType
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Semantic questions of one completion session, answered once and cached here.
 *
 * Completion runs on a copy of the file (a light virtual file without a directory). The semantic
 * layer derives package, directory and module from `originalFile`, so the copy is typed and
 * resolved directly. Only the qualifier and the expected-type site are typed (`typeOf`);
 * candidates use stub-backed declaration types.
 */
class GoCompletionSemantics(private val context: GoCompletionContext) {
    val service: GoSemanticService = GoSemanticService.getInstance(context.file.project)
    private val packageModel = GoPackageModel.getInstance(context.file.project)

    /** The import path of the current package (identity of unexported members). */
    val packagePath: String? by lazy { packageModel.packagePathOf(context.originalFile) }

    /** The package scope of the current file, from the original file (the copy has no directory). */
    val packageScope: GoPackageModel.PackageScope by lazy { packageModel.scopeOf(context.originalFile) }

    /** The expected type at the caret, or null (none, unknown or `any`). */
    val expectedType: GoType? by lazy { GoExpectedTypes.compute(context, this)?.takeIf { it !is GoUnknownType && !isEmptyInterface(it) } }

    /** The type of the [Kind.SELECTOR] qualifier. */
    val qualifierType: GoType by lazy { context.qualifier?.let(::typeOf) ?: GoUnknownType }

    /** The type of [expr] (an expression of the completion copy). */
    fun typeOf(expr: GoExpression): GoType = service.typeOf(expr)

    /** The declaration type of [decl] (a candidate or a declaration before the caret). */
    fun declarationType(decl: GoNamedElement): GoType = service.declarationType(decl)

    /** The type of an element that contains the caret (composite literal, function literal). */
    fun typeOfEnclosing(expr: GoExpression): GoType = service.typeOf(expr)

    /** The package an import spec of the current file denotes. */
    fun importedPackage(spec: GoImportSpec): GoPackage? = packageModel.resolveImport(spec.path, context.originalFile)

    fun resolveImportPath(path: String): GoPackage? = packageModel.resolveImport(path, context.originalFile)

    fun scopeOf(pkg: GoPackage): GoPackageModel.PackageScope = packageModel.scopeOf(pkg)

    /** The imports of the original file (stub-backed, resolvable). */
    val imports: List<GoImportSpec> by lazy { context.originalFile.imports }

    /** Local name of an import (alias, package name or last path element). */
    fun importName(spec: GoImportSpec): String = GoScopes.importName(spec)

    /** What [name] denotes at [place]. */
    fun resolveName(place: PsiElement, name: String): List<GoScopes.Target> = GoScopes.resolveName(place, name)

    /** The signature of the function declaration or literal enclosing the caret. */
    val enclosingSignature: GoSignatureType? by lazy {
        when (val owner = context.functionOwner) {
            is GoFunctionOrMethodDeclaration -> service.declarationType(owner) as? GoSignatureType
            is GoFunctionLit -> typeOfEnclosing(owner) as? GoSignatureType
            else -> null
        }
    }

    /** The type a composite literal's value has, including elided nested literals (`[]T{{...}}`). */
    fun literalType(value: GoLiteralValue): GoType? = literalType(value, service)

    companion object {
        /** [literalType] without a completion session (the intentions of `ide.intentions` ask it too); the walk lives in `GoExpectedType`. */
        fun literalType(value: GoLiteralValue, service: GoSemanticService): GoType? = GoExpectedType.literalType(value, service::typeOf)

        fun derefUnderlying(type: GoType): GoType = GoExpectedType.derefUnderlying(type)

        fun isEmptyInterface(type: GoType): Boolean {
            val u = type.underlying()
            return u is GoInterfaceType && u.isEmpty && type !is GoTypeParamType
        }
    }
}
