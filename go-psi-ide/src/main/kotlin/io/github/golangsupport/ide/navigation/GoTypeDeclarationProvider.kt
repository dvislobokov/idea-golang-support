package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * Go to Type Declaration: from a variable, constant, field, parameter or receiver to the type spec of
 * its type. Pointers, slices, arrays, channels and map values are looked through to the named
 * element type; a function goes to its single result type; an instantiated generic type goes to
 * its origin's declaration; a type parameter to its definition.
 */
class GoTypeDeclarationProvider : TypeDeclarationProvider {

    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        val named = symbol as? GoNamedElement ?: return null
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, symbol.project)) return null
        if (named is GoTypeSpec || named is GoImportSpec || named is GoPackageClause || named is GoLabelDefinition) return null
        val type = GoSemanticService.getInstance(symbol.project).declarationType(named)
        val declaration = typeDeclarationOf(type) ?: return null
        return arrayOf(declaration)
    }

    companion object {
        /** The declaration of the named type reached from [type] through composite types. */
        @JvmStatic
        fun typeDeclarationOf(type: GoType, depth: Int = 0): PsiElement? {
            if (depth > 16) return null
            return when (type) {
                is GoNamedType -> (type.origin ?: type).declaration
                is GoTypeParamType -> type.declaration
                is GoPointerType -> typeDeclarationOf(type.elem, depth + 1)
                is GoSliceType -> typeDeclarationOf(type.elem, depth + 1)
                is GoArrayType -> typeDeclarationOf(type.elem, depth + 1)
                is GoChanType -> typeDeclarationOf(type.elem, depth + 1)
                is GoMapType -> typeDeclarationOf(type.value, depth + 1)
                is GoSignatureType -> if (type.results.size == 1) typeDeclarationOf(type.results[0].type, depth + 1) else null
                else -> null
            }
        }
    }
}
