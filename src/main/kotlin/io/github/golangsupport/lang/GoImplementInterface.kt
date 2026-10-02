package io.github.golangsupport.lang

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import io.github.golangsupport.catalogue.GoCatalogueScanner
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.catalogue.GoInterfaceSources
import io.github.golangsupport.settings.GoSettings
import javax.swing.JList
import javax.swing.ListCellRenderer
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * An interface to implement: of the project, with what is known of it from the scanner, or of the catalogue (the standard library and
 * the modules go.mod requires), of which the name and the package are known until it is chosen and read.
 */
class GoInterfaceCandidate(
    val name: String,
    val importPath: String?,
    val packageName: String,
    /** The directory of a project interface; null for one of the catalogue. */
    val directory: VirtualFile?,
    /** The methods the interface declares itself, null when not read yet. */
    val methods: Int?,
    /** Whether it embeds interfaces, whose methods are counted when it is chosen. */
    val embeds: Boolean,
    /** Whether the type at the caret has every method of it already. */
    val implemented: Boolean,
) {
    val project: Boolean get() = directory != null
    val fullName: String get() = "${importPath ?: packageName}.$name"
    override fun toString(): String = "$name  ${importPath ?: packageName}"
}

/**
 * The popup of Implement Interface (Alt+Insert, Alt+Enter, Ctrl+I), the way Choose by Name works: a search field, the interfaces of
 * the project at once and, with "Non-project" ticked, those of the standard library and of the modules go.mod requires. The methods
 * of the chosen one that the type lacks are written after it, with the types of another package qualified and the package imported.
 */
object GoInterfaceChooser {
    private const val NON_PROJECT = "io.github.golangsupport.implement.nonProject"

    fun show(context: GenerateContext) {
        PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
        val type = context.typeAtCaret ?: return context.hint("Put the caret inside a type declaration")
        val own = GoInterfaceSources.importPathOf(context.project, context.file)
        // The candidates are collected before the popup, under a progress of their own: the popup computes its list as a read task with
        // write action priority and starts over on every write action, so a long scan under it never finished on a big project (seen
        // live: an empty popup, with "Searching..." at the bottom as the only sign). GoProjectInterfaces keeps what was read.
        var collected: Candidates? = null
        val finished = ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val existing = ReadAction.compute<Set<String>, RuntimeException> { context.typeSpecAtCaret?.let(::existingMethods).orEmpty() }
            collected = collect(context, own, existing, type.name, ProgressManager.getInstance().progressIndicator)
        }, "Collecting Interfaces", true, context.project)
        if (!finished) return
        val candidates = collected ?: return
        val model = Model(candidates)
        val popup = ChooseByNamePopup.createPopup(context.project, model, context.file)
        popup.setShowListForEmptyPattern(true)
        popup.setSearchInAnyPlace(true)
        popup.setAdText(
            when {
                !GoSettings.getInstance().completionCatalogue -> "Non-project interfaces need the completion catalogue (Settings | Tools | Go)"
                candidates.catalogue.isEmpty() -> "The catalogue of the standard library and the modules is still being read"
                else -> "Non-project: the standard library and the modules go.mod requires"
            }
        )
        // Ctrl+I opens the popup and, in it, ticks the box: as in GoLand
        ActionManager.getInstance().getAction("ImplementMethods")?.shortcutSet?.let { popup.setCheckBoxShortcut(it) }
        popup.invoke(object : ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any) {
                (element as? GoInterfaceCandidate)?.let { apply(context, it) }
            }
        }, ModalityState.current(), false)
    }

    /**
     * The methods the type of [spec] has, from the type checker: those of `*T`, declared in any file of the package or promoted from an
     * embedded field, are all that a value of `*T` has to implement with.
     */
    fun existingMethods(spec: GoTypeSpec): Set<String> {
        val semantic = GoSemanticService.getInstance(spec.project)
        return semantic.methodsOf(GoPointerType(semantic.declarationType(spec))).mapTo(HashSet()) { it.name }
    }

    /** A pointer receiver, unless the type has methods and none of them takes one: the style of the type is kept. */
    fun pointerReceiver(spec: GoTypeSpec): Boolean {
        val methods = (GoSemanticService.getInstance(spec.project).declarationType(spec) as? GoNamedType)?.methods.orEmpty()
        return methods.isEmpty() || methods.any { it.pointerReceiver }
    }

    /** What Implement Interface writes for [spec]: the stubs of the methods of the interface it lacks and the imports they need. */
    class Plan(val typeName: String, val stubs: String, val imports: List<String>)

    /** The plan for the interface ([importPath], [directory], [name] as [GoInterfaceSources.methodsFor] takes them); null when it is not found. */
    fun plan(spec: GoTypeSpec, target: GoFile, importPath: String?, directory: VirtualFile?, name: String): Plan? {
        val typeName = spec.name ?: return null
        val methods = GoInterfaceSources(spec.project).methodsFor(target, importPath, directory, name) ?: return null
        val existing = existingMethods(spec)
        val missing = methods.filter { it.name !in existing }
        val stubs = if (missing.isEmpty()) "" else GoGenerators.methodStubs(typeName, missing.map { it.name to it.signature }, pointerReceiver(spec))
        return Plan(typeName, stubs, missing.flatMap { it.imports }.distinct())
    }

    private fun apply(context: GenerateContext, chosen: GoInterfaceCandidate) {
        var planned: Plan? = null
        val finished = ProgressManager.getInstance().runProcessWithProgressSynchronously({
            planned = ReadAction.compute<Plan?, RuntimeException> {
                plan(context.typeSpecAtCaret ?: return@compute null, context.file, chosen.importPath, chosen.directory, chosen.name)
            }
        }, "Reading ${chosen.name}", true, context.project)
        if (!finished) return
        val found = planned ?: return context.hint("Cannot read the methods of ${chosen.fullName}")
        if (found.stubs.isEmpty()) return context.hint("${found.typeName} has every method of ${chosen.name}")
        val type = context.typeAtCaret ?: return
        val stubs = found.stubs
        val imports = found.imports
        WriteCommandAction.runWriteCommandAction(context.project, "Implement Interface", null, {
            context.insertAfter(type, stubs, "Implement Interface")
            // the imports go above: the caret, put on the stubs already, moves with the text
            for (path in imports) GoImports.add(context.document.immutableCharSequence, path)?.let { context.document.insertString(it.offset, it.text) }
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
        }, context.file)
    }

    private class Candidates(val project: List<GoInterfaceCandidate>, val catalogue: List<GoInterfaceCandidate>) {
        fun of(nonProject: Boolean): List<GoInterfaceCandidate> = if (nonProject) project + catalogue else project
    }

    private fun collect(context: GenerateContext, own: String?, existing: Set<String>, typeName: String, indicator: ProgressIndicator?): Candidates {
        indicator?.text = "Reading the interfaces of the project"
        val project = projectInterfaces(context, own, existing, typeName, indicator)
        indicator?.text = "Reading the catalogue"
        indicator?.text2 = ""
        val catalogue = ReadAction.compute<List<GoInterfaceCandidate>, RuntimeException> { catalogueInterfaces(context, own) }
        return Candidates(project, catalogue)
    }

    /** Every interface of the project ([GoProjectInterfaces] keeps them read), as seen from the file: `internal` of another tree is left out. */
    private fun projectInterfaces(context: GenerateContext, own: String?, existing: Set<String>, typeName: String, indicator: ProgressIndicator?): List<GoInterfaceCandidate> {
        val file = context.file.virtualFile
        return GoProjectInterfaces.getInstance(context.project).entries(indicator)
            .filter { entry -> !(entry.name == typeName && entry.file == file) && (entry.importPath == null || GoCatalogueScanner.isVisible(entry.importPath, own)) }
            .map { entry -> GoInterfaceCandidate(entry.name, entry.importPath, entry.packageName, entry.directory, entry.methods.size, entry.embeds, !entry.embeds && entry.methods.all { it in existing }) }
            .sortedWith(compareBy({ it.directory != file?.parent }, { it.name }))
    }

    private fun catalogueInterfaces(context: GenerateContext, own: String?): List<GoInterfaceCandidate> =
        GoCatalogueService.getInstance(context.project).current().all(GoDeclarationKind.INTERFACE)
            .filter { !it.project && GoCatalogueScanner.isVisible(it.pack.importPath, own) }
            .map { GoInterfaceCandidate(it.symbol.name, it.pack.importPath, it.pack.name, null, null, false, false) }

    private class Model(val candidates: Candidates) : ChooseByNameModel {
        private fun candidates(nonProject: Boolean): List<GoInterfaceCandidate> = candidates.of(nonProject)

        override fun getPromptText(): String = "Choose interface to implement:"
        override fun getNotInMessage(): String = "No interfaces of the project match"
        override fun getNotFoundMessage(): String = "No interface matches"
        override fun getCheckBoxName(): String = "Non-project"
        override fun loadInitialCheckBoxState(): Boolean = PropertiesComponent.getInstance().getBoolean(NON_PROJECT, true)
        override fun saveInitialCheckBoxState(state: Boolean) = PropertiesComponent.getInstance().setValue(NON_PROJECT, state, true)
        override fun getListCellRenderer(): ListCellRenderer<*> = Renderer()
        override fun getNames(checkBoxState: Boolean): Array<String> = candidates(checkBoxState).map { it.name }.distinct().toTypedArray()
        override fun getElementsByName(name: String, checkBoxState: Boolean, pattern: String): Array<Any> = candidates(checkBoxState).filter { it.name == name }.toTypedArray()
        override fun getElementName(element: Any): String? = (element as? GoInterfaceCandidate)?.name
        override fun getSeparators(): Array<String> = arrayOf(".", "/")
        override fun getFullName(element: Any): String? = (element as? GoInterfaceCandidate)?.fullName
        override fun getHelpId(): String? = null
        override fun willOpenEditor(): Boolean = false
        override fun useMiddleMatching(): Boolean = true
    }

    /** `Priced  example.com/playground/store  1 method`; an interface the type has every method of is greyed out. */
    private class Renderer : ColoredListCellRenderer<Any>() {
        override fun customizeCellRenderer(list: JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val candidate = value as? GoInterfaceCandidate ?: run { append(value?.toString().orEmpty()); return }
            icon = AllIcons.Nodes.Interface
            append(candidate.name, if (candidate.implemented) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("  " + (candidate.importPath ?: candidate.packageName), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            val tail = when {
                candidate.implemented -> "implemented"
                candidate.methods != null -> "${candidate.methods} method${if (candidate.methods == 1) "" else "s"}${if (candidate.embeds) " + embedded" else ""}"
                else -> null
            }
            if (tail != null) append("  $tail", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
        }
    }
}

/** Ctrl+I (Code | Implement Methods) in a Go file: the popup of Implement Interface for the type at the caret. */
class GoImplementMethodsHandler : LanguageCodeInsightActionHandler {
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean = file is GoFile && GenerateContext(file.project, editor, file).typeAtCaret != null
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file is GoFile) GoInterfaceChooser.show(GenerateContext(project, editor, file))
    }
    override fun startInWriteAction(): Boolean = false
}
