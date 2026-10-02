package io.github.golangsupport.lsp

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import org.eclipse.lsp4j.Position

/**
 * The declaration a name stands for, asked of gopls: what the platform needs to start Find Usages, Go to Implementation and the
 * highlighting of usages from a place where the name is used, not declared. Its own resolve knows nothing of our tokens.
 */
class GoplsTargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun getNamedElement(element: PsiElement): PsiElement? {
        if (GoFeatures.native(GoFeature.NAVIGATION, element.project)) return null
        if (element.containingFile !is GoFile || element.elementType != GoTypes.IDENTIFIER) return null
        GoDeclarationPsi.namedOf(element)?.let { return it }
        val file = element.containingFile.virtualFile ?: return null
        val client = Gopls.client(element.project) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val place = Gopls.definition(client, file, Gopls.position(document, element.textRange.startOffset), TIMEOUT_MS).firstOrNull() ?: return null
        // a declaration of the file structure, or the PSI definition of a local: the Find Usages provider of the PSI takes named elements;
        // a package name leads to a directory or a package clause, which is no target to search from
        val target = Gopls.element(element.project, place) ?: return null
        GoDeclarationPsi.declaration(target)?.let { return it }
        return target.takeIf { GoDeclarationPsi.isLocalName(it) && it.containingFile == element.containingFile }?.let { GoDeclarationPsi.namedOf(it) ?: it }
    }

    private companion object {
        const val TIMEOUT_MS = 1500
    }
}

/** Go to Type Declaration (Ctrl+Shift+B): `textDocument/typeDefinition` of gopls, which the platform client does not ask for. */
class GoplsTypeDeclarationProvider : TypeDeclarationProvider {
    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        if (GoFeatures.native(GoFeature.NAVIGATION, symbol.project)) return null
        val leaf = if (symbol is GoNamedElement) symbol.nameIdentifier else symbol
        if (leaf == null || leaf.containingFile !is GoFile) return null
        val file = leaf.containingFile.virtualFile ?: return null
        val client = Gopls.client(leaf.project) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val targets = Gopls.typeDefinition(client, file, Gopls.position(document, leaf.textRange.startOffset), TIMEOUT_MS).mapNotNull { Gopls.element(leaf.project, it) }
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        const val TIMEOUT_MS = 5000
    }
}

/** Type Info (Ctrl+Shift+P) of the name under the caret: the first line of what gopls says on hover, which is its declaration. */
class GoplsExpressionTypeProvider : ExpressionTypeProvider<PsiElement>() {
    override fun getExpressionsAt(elementAt: PsiElement): List<PsiElement> = when {
        GoFeatures.native(GoFeature.HOVER, elementAt.project) -> emptyList()
        elementAt.containingFile is GoFile && elementAt.elementType == GoTypes.IDENTIFIER -> listOf(elementAt)
        else -> emptyList()
    }

    override fun getInformationHint(element: PsiElement): String {
        val file = element.containingFile.virtualFile ?: return errorHint
        val client = Gopls.client(element.project) ?: return errorHint
        val position = ReadAction.compute<Position?, RuntimeException> { FileDocumentManager.getInstance().getDocument(file)?.let { Gopls.position(it, element.textRange.startOffset) } } ?: return errorHint
        val hover = Gopls.hover(client, file, position, TIMEOUT_MS) ?: return errorHint
        return StringUtil.escapeXmlEntities(GoplsHover.declaration(hover) ?: return errorHint)
    }

    override fun getErrorHint(): String = "gopls has no type information for this place"

    private companion object {
        const val TIMEOUT_MS = 5000
    }
}

/** The text of a hover of gopls: a code block with the declaration, then the documentation. */
object GoplsHover {
    /** `var order *Order`, `func NewOrder(currency string) *Order`, `field Name string`: the first line of the code block. */
    fun declaration(markdown: String): String? {
        val lines = markdown.lines()
        val fence = lines.indexOfFirst { it.trimStart().startsWith("```") }
        val body = if (fence >= 0) lines.drop(fence + 1).takeWhile { !it.trimStart().startsWith("```") } else lines
        return body.map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
