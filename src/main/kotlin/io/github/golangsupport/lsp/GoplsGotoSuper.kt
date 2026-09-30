package io.github.golangsupport.lsp

import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import io.github.golangsupport.lang.GoDeclaration
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoFile
import org.eclipse.lsp4j.Position
import java.util.function.Supplier

/**
 * Navigate | Super Method (Ctrl+U) in a Go file: from a method to the method of the interface it implements, from a type to the
 * interfaces it implements. The same request as the I↑ icon of the gutter (`textDocument/implementation` of gopls answers in both
 * directions), as an action for the keyboard. The navigator of the platform asks in the background and shows a list when there are several.
 */
class GoplsGotoSuperHandler : LanguageCodeInsightActionHandler {
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean = file is GoFile && declarationAt(file, editor.caretModel.offset) != null
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val declaration = declarationAt(file, editor.caretModel.offset) ?: return
        val name = declaration.name ?: return
        val title = if (declaration.kind == GoDeclarationKind.METHOD) "Interface Methods $name Implements" else "Interfaces $name Implements"
        PsiTargetNavigator(Supplier<Collection<PsiElement>> { targets(project, declaration) }).navigate(editor, title)
    }

    private fun targets(project: Project, declaration: GoDeclaration): Collection<PsiElement> {
        val (_, file, position) = ReadAction.compute<Triple<Project, VirtualFile, Position>?, RuntimeException> { GoplsTargets.of(declaration) } ?: return emptyList()
        val client = Gopls.client(project) ?: return emptyList()
        return Gopls.implementations(client, file, position, TIMEOUT_MS).mapNotNull { place -> ReadAction.compute<PsiElement?, RuntimeException> { Gopls.element(project, place) } }
    }

    /** The method, or the type, the caret is in: anywhere in its declaration, its body included. */
    private fun declarationAt(file: PsiFile, offset: Int): GoDeclaration? =
        file.findElementAt(offset)?.parentOfType<GoDeclaration>(withSelf = true)?.takeIf { it.kind == GoDeclarationKind.METHOD || it.kind.isType }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
