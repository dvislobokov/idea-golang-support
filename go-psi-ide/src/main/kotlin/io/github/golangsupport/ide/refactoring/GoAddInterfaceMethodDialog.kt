package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoCodeFragments
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent

/**
 * The dialog of Add Method to Interface: the name, the parameters and the results as tables (name and type; add, remove, move up and
 * down; types completed against the interface's file), the method as it will read, "Delegate in wrappers" and the types that get a
 * stub. Validation is [GoAddMethodModel]'s (the field at fault is marked, Refactor is off) and the Go parser's for the types.
 */
class GoAddInterfaceMethodDialog(project: Project, private val iface: GoTypeSpec, context: GoAddMethodContext) : RefactoringDialog(project, true) {

    private val model = GoAddMethodModel(context.interfaceName, context.methods)
    private val goFile = iface.containingFile as? GoFile
    private val nameField = JBTextField(30)
    private val hint = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }
    private val parameters = GoRowsTableModel(emptyList(), PARAMETER_COLUMNS, GoAddMethodModel::newParameter)
    private val parameterTable = GoSignatureTables.table(parameters)
    private val results = GoRowsTableModel(emptyList(), RESULT_COLUMNS, GoAddMethodModel::newResult)
    private val resultTable = GoSignatureTables.table(results)
    private val delegate = JCheckBox("Delegate in wrappers (types holding ${context.interfaceName} in a field call it)", true)
    private val targets = JBList(context.targets).apply {
        emptyText.text = "No types of the project implement ${context.interfaceName}"
        visibleRowCount = 4
        cellRenderer = TargetRenderer()
    }
    private val preview = JTextArea(1, 60).apply {
        isEditable = false
        lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    init {
        title = GoAddInterfaceMethodProcessor.TITLE
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update()
        })
        parameters.addTableModelListener { update() }
        results.addTableModelListener { update() }
        GoSignatureTables.goColumn(parameterTable, 1, project, goFile, GoCodeFragments.Kind.TYPE)
        GoSignatureTables.goColumn(resultTable, 1, project, goFile, GoCodeFragments.Kind.TYPE)
        init()
        update()
    }

    fun options(): GoAddMethodOptions = GoAddMethodOptions(nameField.text.trim(), parameters.rows.toList(), results.rows.toList(), delegate.isSelected)

    private fun problem(): GoAddMethodProblem? {
        val options = options()
        return model.problem(options) ?: ReadAction.compute<GoAddMethodProblem?, RuntimeException> { GoAddInterfaceMethod.typeProblem(project, options) }
    }

    private fun update() {
        preview.text = model.preview(options())
        hint.text = model.hint(nameField.text) ?: ""
        hint.isVisible = hint.text.isNotEmpty()
        validateButtons()
    }

    /** The message under the dialog points at the field at fault; a still empty name only keeps Refactor off. */
    override fun validateButtons() {
        val problem = problem()
        val component = when (problem?.field) {
            GoAddMethodField.PARAMETERS -> parameterTable
            GoAddMethodField.RESULTS -> resultTable
            else -> nameField
        }
        setErrorText(problem?.takeUnless { it.silent }?.message, component)
        refactorAction.isEnabled = problem == null
        previewAction.isEnabled = problem == null
    }

    override fun canRun() {
        problem()?.let { throw ConfigurationException(it.message) }
    }

    override fun doAction() = invokeRefactoring(GoAddInterfaceMethodProcessor(project, iface, options()))

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(hint) }
        row { label("Parameters:") }
        row { cell(GoSignatureTables.decorated(parameterTable, 520, 100)).align(Align.FILL) }.resizableRow()
        row { label("Results:") }
        row { cell(GoSignatureTables.decorated(resultTable, 520, 60)).align(Align.FILL) }.resizableRow()
        row("Signature preview:") { cell(preview).align(AlignX.FILL) }
        row { cell(delegate) }
        row { label("Implementations to update (${targets.model.size}):") }
        row { scrollCell(targets).align(Align.FILL) }.resizableRow()
    }

    /** `ihCachedStore  ihstore.go  wrapper: field next`. */
    private class TargetRenderer : ColoredListCellRenderer<GoAddMethodTarget>() {
        override fun customizeCellRenderer(list: JList<out GoAddMethodTarget>, value: GoAddMethodTarget, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(value.name)
            append("  " + value.file, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.note.isNotEmpty()) append("  " + value.note, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        }
    }

    private companion object {
        val PARAMETER_COLUMNS = listOf(
            GoRowsColumn<GoChangeParameter>("Name", { it.name }, { p, v -> p.copy(name = v) }),
            GoRowsColumn("Type", { it.type }, { p, v -> p.copy(type = v) }),
        )
        val RESULT_COLUMNS = listOf(
            GoRowsColumn<GoChangeResult>("Name", { it.name }, { r, v -> r.copy(name = v) }),
            GoRowsColumn("Type", { it.type }, { r, v -> r.copy(type = v) }),
        )
    }
}

/**
 * Alt+Enter on the name or inside the body of a project interface type: "Add method to interface" opens [GoAddInterfaceMethodDialog]
 * once the interface's methods and implementations are read (with a progress bar). Gated like the other refactorings ([GoIdeFeature.RENAME]).
 */
class GoAddInterfaceMethodIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "Add method to interface"

    override fun getText(): String = familyName

    override fun startInWriteAction(): Boolean = false

    // The action opens a dialog: the preview must not run it on a copy.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        if (element.containingFile !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return false
        val iface = GoAddInterfaceMethod.interfaceAt(element) ?: return false
        return GoImplementations.isInProject(iface) && !GoSignatureHierarchy.isGenerated(iface.containingFile)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val iface = GoAddInterfaceMethod.interfaceAt(element) ?: return
        val context = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<GoAddMethodContext, RuntimeException> { ReadAction.compute<GoAddMethodContext, RuntimeException> { GoAddInterfaceMethod.context(iface) } },
                "Finding Implementations of ${iface.name}", true, project,
            )
        } catch (_: ProcessCanceledException) {
            return
        }
        GoAddInterfaceMethodDialog(project, iface, context).show()
    }
}
