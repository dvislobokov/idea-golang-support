package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.golangsupport.ide.completion.GoCodeFragments
import io.github.golangsupport.lang.psi.GoFile
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent

/**
 * The Change Signature dialog: the name, the parameters as a table (name, type, default value for the calls; add, remove, move up and
 * down), the results as text (`int`, `(int, error)`, `(n int, err error)`) and the new signature as it will read; for an interface
 * method or a method implementing one, "Change the whole hierarchy" (on by default). Refactor and Preview come from
 * [RefactoringDialog]; the work is [GoChangeSignatureProcessor]'s.
 */
class GoChangeSignatureDialog(project: Project, private val target: PsiElement) : RefactoringDialog(project, true) {

    private val initial = GoChangeSignature.initial(target)
    private val oldParameters = GoChangeSignature.parametersOf(GoChangeSignature.signatureOf(target))
    private val nameField = JBTextField(initial.name)
    private val context = target.containingFile as? GoFile
    // Go fields: completion of the types and values of the target's package (GoCodeFragments)
    private val resultsField = GoSignatureTables.field(project, context, GoCodeFragments.Kind.RESULTS, GoChangeSignature.resultsText(initial.results).trim())
    private val hierarchy = JCheckBox("Change the whole hierarchy", true).apply {
        isVisible = GoSignatureHierarchy.applies(target)
    }
    private val model = GoRowsTableModel(initial.parameters, COLUMNS, ::newParameter)
    private val table = GoSignatureTables.table(model)
    private val preview = JTextArea(2, 60).apply {
        isEditable = false
        lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    init {
        title = GoChangeSignatureHandler.TITLE
        val listener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update()
        }
        nameField.document.addDocumentListener(listener)
        resultsField.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = update()
        })
        model.addTableModelListener { update() }
        GoSignatureTables.goColumn(table, 1, project, context, GoCodeFragments.Kind.TYPE)
        GoSignatureTables.goColumn(table, 2, project, context, GoCodeFragments.Kind.EXPRESSION)
        init()
        update()
    }

    fun options(): GoChangeSignatureOptions = GoChangeSignatureOptions(nameField.text.trim(), model.rows.toList(), GoChangeSignature.parseResults(resultsField.text), hierarchy.isSelected)

    private fun update() {
        preview.text = GoChangeSignature.preview(target, options())
        validateButtons()
    }

    override fun canRun() {
        GoChangeSignature.validate(oldParameters, options())?.let { throw ConfigurationException(it) }
    }

    override fun doAction() = invokeRefactoring(GoChangeSignatureProcessor(project, target, options()))

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent {
        val parameters = GoSignatureTables.decorated(table, 520, 140)
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponentFillVertically("Parameters:", parameters)
            .addLabeledComponent("Results:", resultsField)
            .addLabeledComponent("Signature preview:", preview)
            .addComponent(hierarchy)
            .panel
    }

    private companion object {
        // The default value only matters for a new parameter: the calls of an old one keep their argument.
        val COLUMNS = listOf(
            GoRowsColumn<GoChangeParameter>("Name", { it.name }, { p, v -> p.copy(name = v) }),
            GoRowsColumn("Type", { it.type }, { p, v -> p.copy(type = v) }),
            GoRowsColumn("Default value", { if (it.oldIndex < 0) it.defaultValue else "" }, { p, v -> p.copy(defaultValue = v) }, { it.oldIndex < 0 }),
        )

        /** A row added here is a new parameter (old slot -1) that needs a default value for the calls; it goes before a variadic parameter. */
        fun newParameter(rows: List<GoChangeParameter>): Pair<Int, GoChangeParameter> {
            val used = rows.map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "p$it" }.first { it !in used }
            val index = rows.indexOfFirst { it.isVariadic }.takeIf { it >= 0 } ?: rows.size
            return index to GoChangeParameter(if (rows.isNotEmpty() && rows.all { it.name.isEmpty() }) "" else name, "int", -1, "0")
        }
    }
}
