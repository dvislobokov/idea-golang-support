package io.github.golangsupport.semantic.resolve

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReferenceProvider
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes

/** Base of all Go references: poly-variant, cached by [ResolveCache], range = the last identifier. */
abstract class GoReferenceBase<T : PsiElement>(element: T, range: TextRange) : PsiPolyVariantReferenceBase<T>(element, range) {

    abstract fun resolveInner(): List<PsiElement>

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    override fun isReferenceTo(element: PsiElement): Boolean = resolveInner().any { it == element || it.navigationElement == element }

    companion object {
        private val RESOLVER = ResolveCache.PolyVariantResolver<GoReferenceBase<*>> { ref, _ ->
            ref.resolveInner().map { PsiElementResolveResult(it) }.toTypedArray()
        }

        fun identifierRange(element: PsiElement, identifier: PsiElement?): TextRange =
            identifier?.textRangeInParent ?: TextRange(0, element.textLength)
    }
}

/** `x`, `pkg.X`, `v.field`, `v.Method`. */
class GoValueReference(element: GoReferenceExpression) :
    GoReferenceBase<GoReferenceExpression>(element, identifierRange(element, element.identifier)) {
    override fun resolveInner(): List<PsiElement> =
        GoResolver.getInstance(element.project).resolveReferenceExpression(element).mapNotNull { it.element }
}

/** `T`, `pkg.T` in type position. */
class GoTypeReference(element: GoTypeReferenceExpression) :
    GoReferenceBase<GoTypeReferenceExpression>(element, identifierRange(element, element.identifier)) {
    override fun resolveInner(): List<PsiElement> =
        listOfNotNull(GoResolver.getInstance(element.project).resolveTypeReference(element))
}

/** `break L`, `continue L`, `goto L`. */
class GoLabelReference(element: GoLabelRef) : GoReferenceBase<GoLabelRef>(element, identifierRange(element, element.identifier)) {
    override fun resolveInner(): List<PsiElement> = listOfNotNull(GoResolver.getInstance(element.project).resolveLabel(element))
}

/** The import path string of an import spec: resolves to the package directory. */
class GoImportReference(element: GoImportSpec) : GoReferenceBase<GoImportSpec>(element, pathRange(element)) {
    override fun resolveInner(): List<PsiElement> = listOfNotNull(GoResolver.getInstance(element.project).resolveImport(element)?.element)

    companion object {
        private fun pathRange(spec: GoImportSpec): TextRange {
            val literal = spec.stringLiteral ?: return TextRange(0, spec.textLength)
            val r = literal.textRangeInParent
            return if (r.length >= 2) TextRange(r.startOffset + 1, r.endOffset - 1) else r
        }
    }
}

/**
 * `Name` in `T{Name: v}`: the struct field. The reference lives on the key's reference expression
 * (the element it is obtained from, as `PsiReferenceService` requires); [key] is its `Key` parent.
 */
class GoFieldKeyReference(element: GoReferenceExpression, val key: GoKey) :
    GoReferenceBase<GoReferenceExpression>(element, identifierRange(element, element.identifier)) {
    override fun resolveInner(): List<PsiElement> =
        GoResolver.getInstance(element.project).resolveFieldKey(key).mapNotNull { it.element }
}

/** The [GoReferenceProvider] implementation registered as an application service. */
class GoReferenceProviderImpl : GoReferenceProvider {
    override fun getReference(element: PsiElement): PsiReference? = when (element) {
        is GoReferenceExpression -> {
            // Inside a struct literal key the identifier is a field name, not a value.
            val parent = element.parent
            if (parent is GoKey && element.expression == null && GoResolver.getInstance(element.project).isFieldKey(parent)) GoFieldKeyReference(element, parent)
            else GoValueReference(element)
        }
        is GoTypeReferenceExpression -> GoTypeReference(element)
        is GoLabelRef -> GoLabelReference(element)
        is GoImportSpec -> if (element.node.findChildByType(GoTypes.STRING_LITERAL) != null) GoImportReference(element) else null
        else -> null
    }
}
