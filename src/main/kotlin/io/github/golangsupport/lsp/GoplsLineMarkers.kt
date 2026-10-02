package io.github.golangsupport.lsp

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.navigation.PsiTargetNavigator
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes
import java.util.function.Supplier

/**
 * The gutter icons of an IDE that knows the type system, from what gopls knows: "is implemented" at an interface and at its methods,
 * "implements" at a type that satisfies an interface and at the methods it does so with. Go has no `implements` clause, so without the
 * icon the relation is invisible in the code. A click goes to the other side, through a chooser when there is more than one.
 *
 * Both directions are one request: `textDocument/implementation` answers with the implementations of an interface, and with the
 * interfaces of a concrete type. Whether there is an icon at all comes from [GoplsCountsService], which has asked already.
 */
class GoplsImplementationLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != GoTypes.IDENTIFIER) return null
        if (GoFeatures.native(GoFeature.CODE_VISION, element.project)) return null
        val declaration = GoDeclarationKind.ofName(element) ?: return null
        val info = GoDeclarationInfo.of(declaration) ?: return null
        val file = (element.containingFile as? GoFile)?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val project = element.project
        if (Gopls.client(project) == null) return null
        val count = project.service<GoplsCountsService>().counts(file, document.modificationStamp)[GoplsCountsService.key(info)]?.implementations ?: return null
        if (count == 0) return null

        val isInterface = GoplsCountsService.isInterfaceSide(info.kind)
        val what = if (info.kind == GoDeclarationKind.INTERFACE_METHOD || info.kind == GoDeclarationKind.METHOD) "method" else "type"
        val tooltip = if (isInterface) "Is implemented by $count ${what}${if (count == 1) "" else "s"}" else "Implements $count interface${if (what == "method") " method" else ""}${if (count == 1) "" else "s"}"
        val title = if (isInterface) "Implementations of ${info.name}" else "Implemented by ${info.name}"
        val navigation = GutterIconNavigationHandler<PsiElement> { event, clicked ->
            val target = GoDeclarationKind.ofName(clicked) ?: return@GutterIconNavigationHandler
            // the supplier is run by the navigator in the background, with a progress: a request to the server has no place on EDT
            PsiTargetNavigator(Supplier<Collection<PsiElement>> { targets(target) }).navigate(event, title, project)
        }
        return LineMarkerInfo(
            element, element.textRange, if (isInterface) AllIcons.Gutter.ImplementedMethod else AllIcons.Gutter.ImplementingMethod,
            { tooltip }, navigation, GutterIconRenderer.Alignment.RIGHT, { tooltip },
        )
    }

    private fun targets(declaration: GoNamedElement): Collection<PsiElement> {
        val (project, file, position) = ReadAction.compute<Triple<com.intellij.openapi.project.Project, com.intellij.openapi.vfs.VirtualFile, org.eclipse.lsp4j.Position>?, RuntimeException> {
            GoplsTargets.of(declaration)
        } ?: return emptyList()
        val client = Gopls.client(project) ?: return emptyList()
        return Gopls.implementations(client, file, position, TIMEOUT_MS).mapNotNull { place -> ReadAction.compute<PsiElement?, RuntimeException> { Gopls.element(project, place) } }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
