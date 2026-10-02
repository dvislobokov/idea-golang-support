package io.github.golangsupport.semantic.impl

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.types.*
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class GoSemanticServiceImpl(private val project: Project) : GoSemanticService {
    private val typer get() = GoExpressionTyper.getInstance(project)
    private val resolver get() = GoResolver.getInstance(project)

    override fun typeOf(expr: GoExpression): GoType = typer.typeOf(expr)
    override fun declarationType(declaration: GoNamedElement): GoType = typer.declarationType(declaration, null)
    override fun constantValue(expr: GoExpression): GoConstant? = typer.constantOf(expr)
    override fun resolve(reference: GoReferenceExpression): List<PsiElement> = resolver.resolveReferenceExpression(reference).mapNotNull { it.element }
    override fun resolve(reference: GoTypeReferenceExpression): PsiElement? = resolver.resolveTypeReference(reference)
    override fun packageOf(file: GoFile): GoPackage? {
        val dir = io.github.golangsupport.semantic.psi.GoPsiUtil.originalVirtualFile(file).parent ?: return null
        return GoPackageResolver.getInstance(project).packageOf(dir)
    }
    override fun methodsOf(type: GoType): List<GoMethod> = GoLookup.methodSet(type)
    override fun implements(type: GoType, iface: GoInterfaceType): Boolean = GoTypePredicates.implements(type, iface)
    override fun lookupFieldOrMethod(type: GoType, name: String, fromFile: GoFile?): GoLookup.Selection? =
        GoLookup.lookupFieldOrMethod(type, name, fromFile?.let { GoPackageModel.getInstance(project).packagePathOf(it) })
    override fun render(type: GoType, qualified: Boolean): String = GoTypeRenderer.render(type, qualified)
    override fun check(file: GoFile): List<io.github.golangsupport.semantic.api.GoDiagnostic> =
        io.github.golangsupport.semantic.check.GoIncrementalChecker.check(file)
    override fun calleeSignature(call: io.github.golangsupport.lang.psi.GoCallExpr): GoSignatureType? = typer.calleeSignature(call)
}
