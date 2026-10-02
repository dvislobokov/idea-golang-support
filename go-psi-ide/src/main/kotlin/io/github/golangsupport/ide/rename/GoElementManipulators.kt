package io.github.golangsupport.ide.rename

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Manipulators of Go reference sites: a reference's range is its last identifier, so renaming a
 * declaration replaces exactly that identifier leaf. Used by `PsiReference.handleElementRename`.
 */
abstract class GoIdentifierManipulator<T : PsiElement> : AbstractElementManipulator<T>() {

    override fun handleContentChange(element: T, range: TextRange, newContent: String): T {
        val leaf = element.findElementAt(range.startOffset)
        if (leaf == null || leaf.node.elementType !== GoTypes.IDENTIFIER ||
            leaf.textRange.shiftLeft(element.textRange.startOffset) != range
        ) {
            throw IncorrectOperationException("no identifier at $range in '${element.text}'")
        }
        leaf.replace(GoElementFactory.createIdentifier(element.project, newContent))
        return element
    }

    override fun getRangeInElement(element: T): TextRange {
        val identifier = lastIdentifier(element) ?: return TextRange(0, element.textLength)
        return identifier.textRange.shiftLeft(element.textRange.startOffset)
    }

    private fun lastIdentifier(element: PsiElement): PsiElement? {
        var child = element.lastChild
        while (child != null) {
            if (child.node.elementType === GoTypes.IDENTIFIER) return child
            child = child.prevSibling
        }
        return null
    }
}

class GoReferenceExpressionManipulator : GoIdentifierManipulator<GoReferenceExpression>()

class GoTypeReferenceExpressionManipulator : GoIdentifierManipulator<GoTypeReferenceExpression>()

class GoLabelRefManipulator : GoIdentifierManipulator<GoLabelRef>()

/** The import path inside the quotes of an import spec. */
class GoImportSpecManipulator : AbstractElementManipulator<GoImportSpec>() {
    override fun handleContentChange(element: GoImportSpec, range: TextRange, newContent: String): GoImportSpec {
        val text = range.replace(element.text, newContent)
        val file = GoElementFactory.createFileFromText(element.project, "package p\nimport $text\n")
        val spec = PsiTreeUtil.findChildOfType(file, GoImportSpec::class.java)
            ?: throw IncorrectOperationException("cannot create import spec '$text'")
        return element.replace(spec) as GoImportSpec
    }

    override fun getRangeInElement(element: GoImportSpec): TextRange {
        val literal = element.stringLiteral ?: return TextRange(0, element.textLength)
        val r = literal.textRangeInParent
        return if (r.length >= 2) TextRange(r.startOffset + 1, r.endOffset - 1) else r
    }
}
