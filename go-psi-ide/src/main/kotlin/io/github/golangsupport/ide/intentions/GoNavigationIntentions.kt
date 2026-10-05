package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * Alt+Enter navigation as GoLand lists it (low in the list, after the edits): Go to Implementations on an interface or an interface
 * method, Go to Interfaces on a concrete type, Go to Method Specifications on a method. Targets come from [GoImplementations], the same
 * lookup as the gutter markers: implementations in project content, interfaces in project and libraries. Available only when there
 * is a target (the check stops at the first); one target is opened at once, several in the platform's chooser.
 */
abstract class GoNavigationIntention(private val title: String, private val popupTitle: String) : IntentionAction, LowPriorityAction {

    /** The declaration at the caret this intention navigates from, or null. */
    protected abstract fun owner(file: GoFile, offset: Int): GoNamedElement?

    /** Up to [limit] targets for [owner]. */
    protected abstract fun targets(owner: GoNamedElement, limit: Int): List<PsiElement>

    override fun getText(): String = title

    override fun getFamilyName(): String = title

    override fun startInWriteAction(): Boolean = false

    // Navigation only: nothing to preview.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project)) return false
        val owner = owner(file, editor.caretModel.offset) ?: return false
        return targets(owner, 1).isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is GoFile) return
        val owner = owner(file, editor.caretModel.offset) ?: return
        val targets = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<PsiElement>, RuntimeException> { ReadAction.compute<List<PsiElement>, RuntimeException> { targets(owner, Int.MAX_VALUE) } },
                popupTitle, true, project,
            )
        } catch (_: ProcessCanceledException) {
            return
        }
        when (targets.size) {
            0 -> return
            1 -> (targets[0] as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
            else -> PsiTargetNavigator(targets).navigate(editor, popupTitle)
        }
    }
}

/** Where the navigation intentions stand: the header of a type spec or a method, the name of an interface method. */
internal object GoNavigationOwners {

    /** The type spec whose header holds [offset]: its name, the `type` keyword of a single-spec declaration, or the `struct` / `interface` keyword. */
    fun typeSpecAt(file: GoFile, offset: Int): GoTypeSpec? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val spec = PsiTreeUtil.getParentOfType(leaf, GoTypeSpec::class.java, false)
            ?: PsiTreeUtil.getParentOfType(leaf, GoTypeDeclaration::class.java, false)?.typeSpecList?.singleOrNull()
            ?: return null
        if (offset <= spec.identifier.textRange.endOffset) return spec
        val keyword = spec.type?.firstChild
        return spec.takeIf { keyword != null && keyword.firstChild == null && leaf == keyword }
    }

    /** The interface method spec whose name holds [offset]. */
    fun methodSpecAt(file: GoFile, offset: Int): GoMethodSpec? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val spec = PsiTreeUtil.getParentOfType(leaf, GoMethodSpec::class.java, false) ?: return null
        return spec.takeIf { offset <= it.identifier.textRange.endOffset }
    }

    /** The method declaration whose header (from `func` to its `{`) holds [offset]. */
    fun methodAt(file: GoFile, offset: Int): GoMethodDeclaration? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val method = PsiTreeUtil.getParentOfType(leaf, GoMethodDeclaration::class.java, false) ?: return null
        val lbrace = method.block?.lbrace ?: return method
        return method.takeIf { offset <= lbrace.textRange.startOffset }
    }
}

/** On an interface type (its implementing types) or an interface method (the methods implementing it). */
class GoGotoImplementationsIntention : GoNavigationIntention("Go to Implementations", "Choose Implementation") {
    override fun owner(file: GoFile, offset: Int): GoNamedElement? =
        GoNavigationOwners.methodSpecAt(file, offset) ?: GoNavigationOwners.typeSpecAt(file, offset)?.takeIf { GoImplementations.isInterface(it) }

    override fun targets(owner: GoNamedElement, limit: Int): List<PsiElement> {
        val scope = GlobalSearchScope.projectScope(owner.project)
        return when (owner) {
            is GoMethodSpec -> GoImplementations.implementingMethods(owner, scope, limit)
            is GoTypeSpec -> GoImplementations.implementingTypes(owner, scope, limit)
            else -> emptyList()
        }
    }
}

/** On a concrete type: the interfaces its value or pointer implements (GoLand's text). */
class GoGotoInterfacesIntention : GoNavigationIntention("Go to Interfaces", "Choose Interface") {
    override fun owner(file: GoFile, offset: Int): GoNamedElement? =
        GoNavigationOwners.typeSpecAt(file, offset)?.takeIf { !GoImplementations.isInterface(it) }

    override fun targets(owner: GoNamedElement, limit: Int): List<PsiElement> =
        (owner as? GoTypeSpec)?.let { GoImplementations.implementedInterfaces(it, GlobalSearchScope.allScope(it.project), limit) }.orEmpty()
}

/** On a method: the interface method specs it implements. */
class GoGotoMethodSpecificationsIntention : GoNavigationIntention("Go to Method Specifications", "Choose Method Specification") {
    override fun owner(file: GoFile, offset: Int): GoNamedElement? = GoNavigationOwners.methodAt(file, offset)

    override fun targets(owner: GoNamedElement, limit: Int): List<PsiElement> =
        (owner as? GoMethodDeclaration)?.let { GoImplementations.superMethods(it, GlobalSearchScope.allScope(it.project), limit) }.orEmpty()
}
