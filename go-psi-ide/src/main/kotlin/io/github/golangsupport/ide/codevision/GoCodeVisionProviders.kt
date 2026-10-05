package io.github.golangsupport.ide.codevision

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.settings.CodeVisionGroupSettingProvider
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.refactoring.GoAddInterfaceMethod
import io.github.golangsupport.ide.refactoring.GoAddInterfaceMethodIntention
import io.github.golangsupport.ide.refactoring.GoSignatureHierarchy
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec

/**
 * The numbers behind the "3 usages" and "2 implementations" hints above the package-level declarations of a Go file, from the PSI:
 * usages are the references the platform's word-index search finds in the declaration's use scope, implementations come from
 * [GoImplementations] (stub indices, then `implements`), as the gutter markers do.
 *
 * Cost: code vision runs in the daemon, once per change of the file (the platform caches the entries by document stamp), and asks
 * every anchored declaration; a usages search stops at [MAX_COUNT] + 1 references ("100+ usages") and at [MAX_DECLARATIONS]
 * declarations of a file, and checks for cancellation between declarations.
 */
object GoCodeVision {
    const val MAX_DECLARATIONS = 300
    const val MAX_COUNT = 100

    private val TEST_PREFIXES = listOf("Test", "Benchmark", "Fuzz", "Example")

    /**
     * The declarations a hint goes above: functions (not `main`, `init` or the test functions of a `_test.go` file: entry points are used
     * by the toolchain, "no usages" there would be noise), methods, type specs and the method specs of package-level interface types.
     */
    fun anchors(file: GoFile): List<GoNamedElement> {
        val result = ArrayList<GoNamedElement>()
        file.functions.filterTo(result) { isCounted(it, file) }
        result += file.methods
        for (spec in file.types) {
            result += spec
            (spec.type as? GoInterfaceType)?.methodSpecList?.let(result::addAll)
        }
        return result.sortedBy { it.textRange.startOffset }.take(MAX_DECLARATIONS)
    }

    fun isCounted(function: GoFunctionDeclaration, file: GoFile): Boolean {
        val name = function.name ?: return false
        return name != "main" && name != "init" && !(file.isTestFile && isTestFunction(name))
    }

    /** `TestXxx`, `BenchmarkXxx`, `FuzzXxx`, `ExampleXxx` where `Xxx` does not start with a lower-case letter; `TestMain` is not run by `go test`. */
    fun isTestFunction(name: String): Boolean = name != "TestMain" && TEST_PREFIXES.any { prefix ->
        name.startsWith(prefix) && name.getOrNull(prefix.length)?.isLowerCase() != true
    }

    /** The lens is placed by the line of the range start: the declaration without its doc comment, so the hint sits right above the keyword line. */
    fun anchorRange(element: PsiElement): TextRange {
        var child = element.firstChild
        while (child is PsiComment || child is PsiWhiteSpace) child = child.nextSibling
        return TextRange(child?.textRange?.startOffset ?: element.textRange.startOffset, element.textRange.endOffset)
    }

    /** References to [element] in its use scope, at most [MAX_COUNT] + 1 (the search stops there). */
    fun countUsages(element: PsiElement): Int {
        var count = 0
        ReferencesSearch.search(element, element.useScope).forEach { ++count <= MAX_COUNT }
        return count
    }

    /** Implementations of an interface or of an interface method in project content, at most [MAX_COUNT] + 1; null for everything else. */
    fun countImplementations(element: GoNamedElement): Int? {
        val scope = GlobalSearchScope.projectScope(element.project)
        return when (element) {
            is GoTypeSpec -> if (GoImplementations.isInterface(element)) GoImplementations.implementingTypes(element, scope, MAX_COUNT + 1).size else null
            is GoMethodSpec -> GoImplementations.implementingMethods(element, scope, MAX_COUNT + 1).size
            else -> null
        }
    }

    fun usagesText(count: Int): String = when {
        count == 0 -> "no usages"
        count == 1 -> "1 usage"
        count > MAX_COUNT -> "$MAX_COUNT+ usages"
        else -> "$count usages"
    }

    fun implementationsText(count: Int): String = when {
        count == 1 -> "1 implementation"
        count > MAX_COUNT -> "$MAX_COUNT+ implementations"
        else -> "$count implementations"
    }
}

/** The hints of one kind above the declarations of a Go file; a click does what the action of the IDE does with the caret on the name. */
abstract class GoCodeVisionProvider(private val actionId: String) : DaemonBoundCodeVisionProvider {
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Default

    protected abstract fun text(element: GoNamedElement): String?

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.IMPLEMENTATION_MARKERS, file.project)) return emptyList()
        if (DumbService.isDumb(file.project)) return emptyList()
        return GoCodeVision.anchors(file).mapNotNull { element ->
            ProgressManager.checkCanceled()
            val text = text(element) ?: return@mapNotNull null
            val offset = element.nameIdentifier?.textRange?.startOffset ?: element.textOffset
            GoCodeVision.anchorRange(element) to ClickableTextCodeVisionEntry(text, id, { _, clickedEditor -> invoke(clickedEditor, offset) }, null, text, "", emptyList())
        }
    }

    private fun invoke(editor: Editor, offset: Int) {
        editor.caretModel.moveToOffset(offset)
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "GoCodeVision", true)
    }
}

class GoUsagesCodeVisionProvider : GoCodeVisionProvider("ShowUsages") {
    override val id: String get() = "go.psi.usages"
    override val name: String get() = "Go usages"

    /** The group of the platform: Settings | Editor | Inlay Hints | Code vision | Usages switches these too. */
    override val groupId: String get() = "references"
    override fun text(element: GoNamedElement): String = GoCodeVision.usagesText(GoCodeVision.countUsages(element))
}

class GoImplementationsCodeVisionProvider : GoCodeVisionProvider("GotoImplementation") {
    override val id: String get() = "go.psi.implementations"
    override val name: String get() = "Go implementations"
    override val groupId: String get() = "inheritors"

    /** For interfaces and their methods only, and only when there is one: what a type implements is said by the icon in the gutter. */
    override fun text(element: GoNamedElement): String? = GoCodeVision.countImplementations(element)?.takeIf { it > 0 }?.let(GoCodeVision::implementationsText)
}

/**
 * A code vision action above a declaration, as GoLand has them: its own group (Settings | Editor | Inlay Hints | Code vision, through
 * [GoActionCodeVisionSettings]), above the declaration line by default. The usages / implementations hints keep [CodeVisionAnchorKind.Default]:
 * where they go is the platform's setting (the group's position, else the default position), GoLand's "at the line end" is its product default.
 */
abstract class GoActionCodeVisionProvider : DaemonBoundCodeVisionProvider {
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()
    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Top
    override val groupId: String get() = id

    /** The type specs a lens goes above, with the element whose range anchors it. */
    protected abstract fun targets(file: GoFile): List<Pair<PsiElement, GoTypeSpec>>

    protected abstract fun text(): String

    protected abstract fun invoke(editor: Editor, spec: GoTypeSpec)

    protected open fun enabled(file: GoFile): Boolean = GoIdeFeatureGate.enabled(GoIdeFeature.IMPLEMENTATION_MARKERS, file.project)

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file !is GoFile || !enabled(file) || DumbService.isDumb(file.project)) return emptyList()
        return targets(file).take(GoCodeVision.MAX_DECLARATIONS).map { (anchor, spec) ->
            ProgressManager.checkCanceled()
            val pointer = SmartPointerManager.createPointer(spec)
            GoCodeVision.anchorRange(anchor) to ClickableTextCodeVisionEntry(text(), id, { _, clicked -> pointer.element?.let { invoke(clicked, it) } }, null, text(), name, emptyList())
        }
    }
}

/**
 * "Implement interface" above every type declaration with a non-interface type (structs, `type Level int`, generic types), once per
 * `type ( … )` group as GoLand shows it: a click puts the caret on the type's name and runs Implement Methods (Ctrl+I), which the host
 * answers with its interface chooser (`codeInsight.implementMethod` for Go).
 */
class GoImplementInterfaceCodeVisionProvider : GoActionCodeVisionProvider() {
    override val id: String get() = ID
    override val name: String get() = "Implement interface"

    override fun targets(file: GoFile): List<Pair<PsiElement, GoTypeSpec>> = file.children.filterIsInstance<GoTypeDeclaration>().mapNotNull { declaration ->
        declaration.typeSpecList.firstOrNull { !it.isAlias && it.type != null && !GoImplementations.isInterface(it) }?.let { declaration to it }
    }

    override fun text(): String = "Implement interface"

    override fun invoke(editor: Editor, spec: GoTypeSpec) {
        editor.caretModel.moveToOffset(spec.identifier.textRange.startOffset)
        val action = ActionManager.getInstance().getAction(IMPLEMENT_METHODS) ?: return
        ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "GoCodeVision", true)
    }

    companion object {
        const val ID = "go.psi.implement.interface"

        /** Code | Implement Methods (Ctrl+I) of the platform. */
        const val IMPLEMENT_METHODS = "ImplementMethods"
    }
}

/**
 * "Add method" above a package-level interface of the project that has an implementation in the project: a click opens Add Method to
 * Interface ([GoAddInterfaceMethodIntention]: the method goes into the interface, stubs into every implementing type). Gated like that
 * refactoring ([GoIdeFeature.RENAME]) and like the other hints ([GoIdeFeature.IMPLEMENTATION_MARKERS]).
 */
class GoAddInterfaceMethodCodeVisionProvider : GoActionCodeVisionProvider() {
    override val id: String get() = ID
    override val name: String get() = "Add method to interface and all its implementations"

    override fun enabled(file: GoFile): Boolean = super.enabled(file) && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, file.project)

    override fun targets(file: GoFile): List<Pair<PsiElement, GoTypeSpec>> {
        if (GoSignatureHierarchy.isGenerated(file) || !GoImplementations.isInProject(file)) return emptyList()
        val scope = GlobalSearchScope.projectScope(file.project)
        return file.types.filter { spec ->
            ProgressManager.checkCanceled()
            GoAddInterfaceMethod.interfaceAt(spec.identifier) == spec && GoImplementations.implementingTypes(spec, scope, 1).isNotEmpty()
        }.map { it to it }
    }

    override fun text(): String = "Add method"

    override fun invoke(editor: Editor, spec: GoTypeSpec) {
        val project = editor.project ?: return
        editor.caretModel.moveToOffset(spec.identifier.textRange.startOffset)
        GoAddInterfaceMethodIntention().invoke(project, editor, spec.identifier)
    }

    companion object {
        const val ID = "go.psi.add.interface.method"
    }
}

/** The names and descriptions of the groups of [GoActionCodeVisionProvider] in Settings | Editor | Inlay Hints | Code vision. */
abstract class GoActionCodeVisionSettings(override val groupId: String, override val groupName: String, override val description: String) :
    CodeVisionGroupSettingProvider

class GoImplementInterfaceCodeVisionSettings : GoActionCodeVisionSettings(
    GoImplementInterfaceCodeVisionProvider.ID, "Implement interface", "Above a Go type declaration: implement the methods of an interface for the type (Ctrl+I).",
)

class GoAddInterfaceMethodCodeVisionSettings : GoActionCodeVisionSettings(
    GoAddInterfaceMethodCodeVisionProvider.ID, "Add method to interface and all its implementations",
    "Above a Go interface with implementations in the project: add a method to it and a stub of it to every implementing type.",
)
