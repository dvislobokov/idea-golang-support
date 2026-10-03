package io.github.golangsupport.ide.refactoring

import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.rename.GoRenamePackageProcessor
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * F6 on package-level Go declarations: the declaration at the caret (or the one a reference at the caret names), the ones a
 * selection touches, or the ones selected in the Project / Structure view. Opens [GoMoveDialog]; [GoMoveProcessor] does the rest.
 * Files and directories stay with the platform's handler. Stands down while [GoIdeFeature.RENAME] is off.
 */
class GoMoveHandler : MoveHandlerDelegate() {

    override fun supportsLanguage(language: Language): Boolean = language.isKindOf(GoLanguage)

    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?, reference: PsiReference?): Boolean =
        elements.isNotEmpty() && elements.all { movable(it) } && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, elements.first().project)

    override fun isValidTarget(targetElement: PsiElement?, sources: Array<out PsiElement>): Boolean = targetElement is PsiDirectory || targetElement is GoFile

    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, callback: MoveCallback?) = show(project, elements.toList(), null, targetContainer)

    override fun tryToMove(element: PsiElement, project: Project, dataContext: DataContext?, reference: PsiReference?, editor: Editor?): Boolean {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return false
        val file = element.containingFile as? GoFile ?: return false
        val selection = editor?.selectionModel?.takeIf { it.hasSelection() && reference == null }
        val elements = if (selection != null && editor.document == PsiDocumentManager.getInstance(project).getDocument(file)) {
            GoMoveDeclarations.inRange(file, TextRange(selection.selectionStart, selection.selectionEnd))
        } else listOfNotNull(GoMoveDeclarations.movable(element))
        if (elements.isEmpty() || !elements.all { movable(it) }) return false
        show(project, elements, editor, null)
        return true
    }

    override fun getActionName(elements: Array<out PsiElement>): String = "Move Declarations..."

    private fun movable(e: PsiElement): Boolean =
        GoMoveDeclarations.movable(e) != null && GoRenamePackageProcessor.inProjectContent(e.containingFile?.containingDirectory)

    private fun show(project: Project, elements: List<PsiElement>, editor: Editor?, targetContainer: PsiElement?) {
        val source = elements.first().containingFile as? GoFile ?: return
        val units = try {
            GoMoveDeclarations.units(elements, withMethods = false, crossPackage = false)
        } catch (e: GoMoveRefusal) {
            return CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n${e.message}", GoMoveProcessor.TITLE, null)
        }
        val initialDir = (targetContainer as? PsiDirectory)?.virtualFile ?: (targetContainer as? GoFile)?.virtualFile?.parent ?: source.virtualFile?.parent ?: return
        val initialFile = (targetContainer as? GoFile)?.name ?: ""
        val hasTypes = units.any { u -> u.element is GoTypeSpec || u.element is GoTypeDeclaration }
        val dialog = GoMoveDialog(project, GoMoveDeclarations.describe(units), initialDir.path, initialFile, hasTypes, source.packageName.orEmpty())
        if (!dialog.showAndGet()) return
        val processor = try {
            GoMoveProcessor.create(project, elements, dialog.options())
        } catch (e: GoMoveRefusal) {
            return CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n${e.message}", GoMoveProcessor.TITLE, null)
        }
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, units.map { it.file }, true)) return
        processor.setPreviewUsages(dialog.isPreviewUsages)
        processor.run()
    }
}
