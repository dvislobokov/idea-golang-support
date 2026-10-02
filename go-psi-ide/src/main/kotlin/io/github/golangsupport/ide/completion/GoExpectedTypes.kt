package io.github.golangsupport.ide.completion

import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.infer.GoExpectedType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType

/**
 * The type an expression at the caret is expected to have: `GoSemanticService.expectedTypeAt` of the
 * reference being completed (or of the composite literal whose type is being typed). Null when the
 * position has no expectation.
 */
object GoExpectedTypes {

    fun compute(context: GoCompletionContext, semantics: GoCompletionSemantics): GoType? {
        val expr: GoExpression = context.reference
            ?: (context.typeReference?.parent as? GoCompositeLit)
            ?: return null
        return semantics.service.expectedTypeAt(expr)
    }

    /** The parameter type for argument [index] (variadic tail: the element type). */
    fun parameterType(signature: GoSignatureType, index: Int): GoType? = GoExpectedType.parameterType(signature, index)

    /** A field (promoted ones included) by name, breadth-first. */
    fun fieldType(struct: GoStructType, name: String): GoType? = GoExpectedType.fieldType(struct, name)
}
