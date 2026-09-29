package io.github.golangsupport.mod

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

/** `require (...)`, `replace (...)`, `exclude`, `retract`, `tool`, `use`: the blocks of go.mod and go.work fold to their directive. */
class GoModFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.immutableCharSequence
        val result = ArrayList<FoldingDescriptor>()
        var open: ASTNode? = null
        var node = root.node.firstChildNode
        while (node != null) {
            when (node.elementType) {
                GoModTokenTypes.LPAREN -> open = node
                GoModTokenTypes.RPAREN -> {
                    val start = open?.startOffset
                    if (start != null && document.getLineNumber(start) < document.getLineNumber(node.startOffset)) {
                        result += FoldingDescriptor(open, TextRange(start, node.startOffset + 1))
                    }
                    open = null
                }
            }
            node = node.treeNext
        }
        if (text.isEmpty()) return emptyArray()
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "(...)"
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
