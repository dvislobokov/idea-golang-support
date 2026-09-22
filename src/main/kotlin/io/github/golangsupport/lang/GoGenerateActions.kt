package io.github.golangsupport.lang

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Alt+Insert in a Go file (the Generate popup of the platform): a constructor, getters and setters, `String()`, struct tags, the
 * methods of an interface, a test. What is generated goes after the declaration the caret is in (or at the end of the file), the
 * test into `name_test.go` next to the file.
 */
abstract class GoGenerateAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val context = context(e)
        e.presentation.isEnabledAndVisible = context != null && isAvailable(context)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = context(e) ?: return
        perform(context)
    }

    protected open fun isAvailable(context: GenerateContext): Boolean = true
    protected abstract fun perform(context: GenerateContext)

    private fun context(e: AnActionEvent): GenerateContext? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.PSI_FILE) as? GoFile ?: return null
        return GenerateContext(e.project ?: return null, editor, file)
    }
}

class GenerateContext(val project: Project, val editor: Editor, val file: GoFile) {
    val document: Document get() = editor.document
    val structure: GoFileStructure get() = GoDeclarations.scan(document.immutableCharSequence)
    val offset: Int get() = editor.caretModel.offset

    /** The type declaration the caret stands in, or on the name of. */
    val typeAtCaret: GoDeclarationInfo? get() = structure.declarations.filter { it.kind.isType }.lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }
    val structAtCaret: GoDeclarationInfo? get() = typeAtCaret?.takeIf { it.kind == GoDeclarationKind.STRUCT }
    val functionAtCaret: GoDeclarationInfo? get() = structure.declarations.filter { it.kind == GoDeclarationKind.FUNCTION || it.kind == GoDeclarationKind.METHOD }.lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }

    /** The declaration the generated code goes after: the one at the caret, and the last method of its type after that. */
    fun insertionOffset(declaration: GoDeclarationInfo?): Int {
        val text = document.immutableCharSequence
        if (declaration == null) return text.length
        val methods = structure.declarations.filter { it.kind == GoDeclarationKind.METHOD && it.receiver == declaration.name }
        val after = (methods + declaration).maxOf { it.range.endOffset }
        val lineEnd = text.indexOf('\n', after).let { if (it < 0) text.length else it }
        return lineEnd.coerceAtMost(text.length)
    }

    /** Writes [code] after [declaration] with a blank line before it, then puts the caret on its first line. */
    fun insertAfter(declaration: GoDeclarationInfo?, code: String, title: String) {
        val at = insertionOffset(declaration)
        val text = document.immutableCharSequence
        val prefix = if (at == 0 || text[at - 1] == '\n' && (at < 2 || text[at - 2] == '\n')) "" else if (at >= text.length && !text.endsWith("\n")) "\n\n" else "\n"
        val block = "$prefix$code"
        WriteCommandAction.runWriteCommandAction(project, title, null, {
            document.insertString(at, block)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            editor.caretModel.moveToOffset(at + prefix.length)
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        }, file)
    }

    fun hint(message: String) = HintManager.getInstance().showErrorHint(editor, message)
}

/** The struct at the caret with the fields chosen in a dialog; [title] names the dialog. */
private fun GenerateContext.chooseFields(title: String): Pair<GoDeclarationInfo, List<GoDeclarationInfo>>? {
    val struct = structAtCaret ?: run { hint("Put the caret inside a struct type"); return null }
    val fields = GoGenerators.fields(struct)
    if (fields.isEmpty()) { hint("${struct.name} has no fields"); return null }
    val dialog = FieldsDialog(project, title, struct.name, fields)
    if (!dialog.showAndGet()) return null
    val chosen = dialog.chosen()
    return if (chosen.isEmpty()) null else struct to chosen
}

class GoGenerateConstructorAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) {
        val (struct, fields) = context.chooseFields("Constructor") ?: return
        context.insertAfter(struct, GoGenerators.constructor(struct, fields), "Generate Constructor")
    }
}

class GoGenerateGettersAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) {
        val (struct, fields) = context.chooseFields("Getters") ?: return
        context.insertAfter(struct, GoGenerators.getters(struct, fields), "Generate Getters")
    }
}

class GoGenerateSettersAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) {
        val (struct, fields) = context.chooseFields("Setters") ?: return
        context.insertAfter(struct, GoGenerators.setters(struct, fields), "Generate Setters")
    }
}

class GoGenerateGettersAndSettersAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) {
        val (struct, fields) = context.chooseFields("Getters and Setters") ?: return
        context.insertAfter(struct, GoGenerators.getters(struct, fields) + "\n" + GoGenerators.setters(struct, fields), "Generate Getters and Setters")
    }
}

class GoGenerateStringAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) {
        val (struct, fields) = context.chooseFields("String() Method") ?: return
        context.insertAfter(struct, GoGenerators.stringMethod(struct, fields), "Generate String()")
    }
}

/** Struct tags: `json`, `yaml`, `xml`, `db`, `mapstructure` in the case of one's choosing, added to the fields of the struct at the caret. */
class GoGenerateStructTagsAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.structAtCaret != null
    override fun perform(context: GenerateContext) = addTags(context)

    companion object {
        fun addTags(context: GenerateContext) {
            val struct = context.structAtCaret ?: return context.hint("Put the caret inside a struct type")
            val body = struct.body ?: return
            val fields = GoGenerators.fields(struct)
            if (fields.isEmpty()) return context.hint("${struct.name} has no named fields")
            val dialog = TagsDialog(context.project)
            if (!dialog.showAndGet()) return
            val kinds = dialog.kinds()
            if (kinds.isEmpty()) return
            val text = context.document.getText(body)
            val replacement = GoGenerators.withTags(text, fields, body.startOffset, kinds, dialog.case(), dialog.omitEmpty())
            if (replacement == text) return context.hint("Every field has these tags already")
            WriteCommandAction.runWriteCommandAction(context.project, "Add Struct Tags", null, {
                context.document.replaceString(body.startOffset, body.endOffset, replacement)
            }, context.file)
        }
    }
}

/** Implement Interface: the interfaces of the project by name, from the index; the missing methods of the chosen one are written after the type. */
class GoImplementInterfaceAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.typeAtCaret != null
    override fun perform(context: GenerateContext) = implement(context)

    companion object {
        fun implement(context: GenerateContext) {
            val type = context.typeAtCaret ?: return context.hint("Put the caret inside a type declaration")
            val interfaces = interfaces(context.project, context.file)
            if (interfaces.isEmpty()) return context.hint("No interfaces are found in the project")
            JBPopupFactory.getInstance().createPopupChooserBuilder(interfaces)
                .setTitle("Implement Interface")
                .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.title })
                .setNamerForFiltering { it.title }
                .setItemChosenCallback { chosen ->
                    val existing = context.structure.declarations.filter { it.kind == GoDeclarationKind.METHOD && it.receiver == type.name }.map { it.name }.toSet()
                    val missing = GoGenerators.missingMethods(chosen.declaration, existing)
                    if (missing.children.isEmpty()) return@setItemChosenCallback context.hint("${type.name} has every method of ${chosen.declaration.name}")
                    context.insertAfter(type, GoGenerators.interfaceStubs(type.name, missing), "Implement Interface")
                }
                .createPopup().showInBestPositionFor(context.editor)
        }

        class Candidate(val declaration: GoDeclarationInfo, val fileName: String, val packageName: String?) {
            val title: String get() = (if (packageName != null) "$packageName." else "") + declaration.name + "  " + fileName
        }

        /** Every interface with methods in the Go files of the project: the ones of this file first, then by name. */
        fun interfaces(project: Project, current: GoFile): List<Candidate> {
            val result = ArrayList<Candidate>()
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            // the index knows names, not kinds: every Go file of the project is looked at, once per Implement Interface
            for (file in com.intellij.psi.search.FileTypeIndex.getFiles(GoFileType, scope)) {
                val psi = PsiManager.getInstance(project).findFile(file) as? GoFile ?: continue
                val structure = GoStructure.of(psi)
                structure.declarations.filter { it.kind == GoDeclarationKind.INTERFACE && it.children.any { m -> m.kind == GoDeclarationKind.INTERFACE_METHOD } }
                    .forEach { result += Candidate(it, file.name, structure.packageName) }
            }
            return result.sortedWith(compareBy({ it.fileName != current.name }, { it.declaration.name }))
        }
    }
}

/** A table-driven test for the function at the caret, in `<file>_test.go` next to it (made when it is not there). */
class GoGenerateTestAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.functionAtCaret != null && !context.file.isTestFile
    override fun perform(context: GenerateContext) = generate(context)

    companion object {
        fun generate(context: GenerateContext) {
            val function = context.functionAtCaret ?: return context.hint("Put the caret inside a function")
            val source = context.file.virtualFile ?: return
            val packageName = context.structure.packageName
            val testName = source.nameWithoutExtension + "_test.go"
            val test = GoGenerators.testFunction(function, packageName)
            WriteCommandAction.runWriteCommandAction(context.project, "Generate Test", null, {
                val directory = source.parent
                val file = directory.findChild(testName) ?: directory.createChildData(this, testName).also { created ->
                    VfsUtil.saveText(created, "package ${packageName ?: "main"}\n\nimport \"testing\"\n")
                }
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return@runWriteCommandAction
                val text = document.text
                if (Regex("""func Test${Regex.escape(if (function.receiver != null) "${function.receiver}_${function.name}" else function.name)}\(""").containsMatchIn(text)) {
                    context.hint("$testName has a test of ${function.name} already")
                } else {
                    document.insertString(text.length, (if (text.endsWith("\n")) "\n" else "\n\n") + test)
                }
                PsiDocumentManager.getInstance(context.project).commitDocument(document)
                FileEditorManager.getInstance(context.project).openTextEditor(OpenFileDescriptor(context.project, file, document.textLength - test.length + 1), true)
            }, context.file)
        }
    }
}

/** The fields to generate for: all ticked at the start. */
private class FieldsDialog(project: Project, title: String, typeName: String, private val fields: List<GoDeclarationInfo>) : DialogWrapper(project) {
    private val list = CheckBoxList<GoDeclarationInfo>()

    init {
        this.title = "$title of $typeName"
        fields.forEach { list.addItem(it, "${it.name} ${it.signature}", true) }
        init()
    }

    fun chosen(): List<GoDeclarationInfo> = fields.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = com.intellij.ui.ScrollPaneFactory.createScrollPane(list).apply {
        preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(360), com.intellij.util.ui.JBUI.scale(240))
    }

    override fun getPreferredFocusedComponent(): JComponent = list
}

private class TagsDialog(project: Project) : DialogWrapper(project) {
    private val boxes = listOf("json", "yaml", "xml", "db", "mapstructure", "toml").associateWith { JBCheckBox(it, it == "json") }
    private val case = ComboBox(GoGenerators.TagCase.entries.toTypedArray())
    private val omitEmpty = JBCheckBox("omitempty for json", false)

    init {
        title = "Add Struct Tags"
        init()
    }

    fun kinds(): List<String> = boxes.filterValues { it.isSelected }.keys.toList()
    fun case(): GoGenerators.TagCase = case.selectedItem as GoGenerators.TagCase
    fun omitEmpty(): Boolean = omitEmpty.isSelected

    override fun createCenterPanel(): JComponent = panel {
        row("Tags:") { boxes.values.forEach { cell(it) } }
        row("Names:") { cell(case) }
        row { cell(omitEmpty) }
    }
}
