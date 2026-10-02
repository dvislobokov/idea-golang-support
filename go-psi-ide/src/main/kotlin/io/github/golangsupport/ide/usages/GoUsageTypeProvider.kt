package io.github.golangsupport.ide.usages

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * Usage kinds shown in the Find Usages tree: calls, method calls, field reads/writes, struct
 * literal keys, type references, embeddings, conversions, package qualifiers, imports, labels and
 * plain variable reads/writes.
 */
class GoUsageTypeProvider : UsageTypeProvider {

    override fun getUsageType(element: PsiElement): UsageType? {
        val site = PsiTreeUtil.getNonStrictParentOfType(
            element,
            GoReferenceExpression::class.java,
            GoTypeReferenceExpression::class.java,
            GoLabelRef::class.java,
            GoImportSpec::class.java,
        ) ?: return null
        return when (site) {
            is GoLabelRef -> LABEL
            is GoImportSpec -> IMPORT
            is GoTypeReferenceExpression -> typeUsage(site)
            is GoReferenceExpression -> valueUsage(site)
            else -> null
        }
    }

    private fun typeUsage(ref: GoTypeReferenceExpression): UsageType {
        // `pkg` in `pkg.T`: the qualifier is a reference expression inside the type reference.
        if (ref.parent is GoAnonymousFieldDefinition) return EMBEDDING
        return TYPE_USAGE
    }

    private fun valueUsage(ref: GoReferenceExpression): UsageType {
        // A qualifier `pkg` in a type reference `pkg.T`.
        if (ref.parent is GoTypeReferenceExpression) return PACKAGE_QUALIFIER
        if (GoAccess.isFieldKey(ref)) return LITERAL_KEY
        val target = ref.reference?.resolve()
        if (target is GoImportSpec) return PACKAGE_QUALIFIER
        val called = isCallee(ref)
        return when (target) {
            is GoMethodDeclaration, is GoMethodSpec -> if (called) METHOD_CALL else METHOD_VALUE
            is GoTypeSpec, is GoTypeParamDefinition -> if (called) CONVERSION else TYPE_USAGE
            is GoFieldDefinition, is GoAnonymousFieldDefinition -> when {
                called -> CALL
                GoAccess.isWrite(ref) -> FIELD_WRITE
                else -> FIELD_READ
            }
            else -> when {
                called -> CALL
                GoAccess.isWrite(ref) -> UsageType.WRITE
                else -> UsageType.READ
            }
        }
    }

    companion object {
        @JvmField val CALL = UsageType { "Call" }
        @JvmField val METHOD_CALL = UsageType { "Method call" }
        @JvmField val METHOD_VALUE = UsageType { "Method value" }
        @JvmField val FIELD_READ = UsageType { "Field read" }
        @JvmField val FIELD_WRITE = UsageType { "Field write" }
        @JvmField val LITERAL_KEY = UsageType { "Composite literal key" }
        @JvmField val TYPE_USAGE = UsageType { "Type reference" }
        @JvmField val EMBEDDING = UsageType { "Embedded field" }
        @JvmField val CONVERSION = UsageType { "Type conversion" }
        @JvmField val PACKAGE_QUALIFIER = UsageType { "Package qualifier" }
        @JvmField val IMPORT = UsageType { "Import" }
        @JvmField val LABEL = UsageType { "Label reference" }

        /** Whether [ref] is the function operand of a call, possibly parenthesized or instantiated (`f[int](x)`). */
        @JvmStatic
        fun isCallee(ref: GoReferenceExpression): Boolean {
            var e: PsiElement = ref
            while (true) {
                val parent = e.parent
                e = when {
                    parent is GoParenthesesExpr -> parent
                    parent is GoIndexOrSliceExpr && parent.expression == e -> parent
                    else -> break
                }
            }
            val call = e.parent as? GoCallExpr ?: return false
            return call.expression == e
        }
    }
}
