package io.github.golangsupport.lang.psi.impl

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoFunctionType
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import org.jetbrains.annotations.ApiStatus

/**
 * Use scopes of Go declarations, so that Find Usages and rename search only where a name can be
 * referenced:
 *
 * - imports: the containing file;
 * - labels and declarations inside function bodies: the enclosing function (declaration or literal);
 * - parameters, receivers and type parameters: the declaring function, function literal, function
 *   type, interface method or type spec;
 * - unexported package-level names (including fields and interface methods): the package directory;
 * - exported names: project and libraries (plus the platform default, so
 *   declarations outside any root, e.g. an unindexed GOROOT, are still searched for in the project);
 * - the package clause: the platform default.
 *
 * Elements with a green stub are package-level by construction (nothing inside function bodies is
 * stubbed), so the body check does not load the AST for them.
 */
@ApiStatus.Internal
object GoUseScopes {

    @JvmStatic
    fun useScope(element: GoNamedElement, default: () -> SearchScope): SearchScope {
        when (element) {
            is GoImportSpec -> return element.containingFile?.let(::LocalSearchScope) ?: default()
            is GoPackageClause -> return default()
            is GoLabelDefinition -> return bodyOwner(element)?.let(::LocalSearchScope) ?: default()
            is GoParamDefinition, is GoReceiver, is GoTypeParamDefinition ->
                return signatureOwner(element)?.let(::LocalSearchScope) ?: default()
        }
        if (isInsideFunctionBody(element)) return bodyOwner(element)?.let(::LocalSearchScope) ?: default()
        if (GoPsiImplUtil.isExported(element.name)) return GlobalSearchScope.allScope(element.project).union(default())
        val directory = element.containingFile?.originalFile?.containingDirectory ?: return default()
        return GlobalSearchScopesCore.directoryScope(directory, false)
    }

    /** True inside a function body; elements with a green stub never are (no AST load). */
    @JvmStatic
    fun isInsideFunctionBody(element: PsiElement): Boolean {
        var e: PsiElement? = element
        while (e != null && e !is PsiFile) {
            if (e is StubBasedPsiElementBase<*> && e.greenStub != null) return false
            if (e is GoBlock) return true
            e = e.parent
        }
        return false
    }

    /** The function (declaration or literal) whose body contains [element]. */
    private fun bodyOwner(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, GoFunctionLit::class.java, GoFunctionOrMethodDeclaration::class.java)

    /** The nearest declaration whose signature or type parameter list declares [element]. */
    private fun signatureOwner(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(
            element,
            GoFunctionLit::class.java,
            GoMethodSpec::class.java,
            GoFunctionType::class.java,
            GoTypeSpec::class.java,
            GoFunctionOrMethodDeclaration::class.java,
        )
}
