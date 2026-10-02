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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeSpec
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import io.github.golangsupport.lang.psi.GoFile

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
        // the struct and its fields come from the PSI: it has to see what is typed
        PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
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

    /** The top-level type spec the caret stands in, or on the keyword or the name of, from the PSI. Needs read access. */
    val typeSpecAtCaret: GoTypeSpec? get() = GoStructPsi.typeSpecAt(file, offset)

    /** [typeSpecAtCaret] as the tools that take declarations of the scanner want it ([GoStructPsi.infoOf]). */
    val typeAtCaret: GoDeclarationInfo? get() = typeSpecAtCaret?.let(GoStructPsi::infoOf)
    val structAtCaret: GoDeclarationInfo? get() = typeAtCaret?.takeIf { it.kind == GoDeclarationKind.STRUCT }

    /** The struct type at the caret with its fields from the PSI. */
    val structTypeAtCaret: GoStructType? get() = typeSpecAtCaret?.let(GoStructPsi::structOf)
    val functionAtCaret: GoDeclarationInfo? get() = structure.declarations.filter { it.kind == GoDeclarationKind.FUNCTION || it.kind == GoDeclarationKind.METHOD }.lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }

    /** The declaration the generated code goes after: the one at the caret, and the last method of its type after that. */
    fun insertionOffset(declaration: GoDeclarationInfo?): Int {
        val text = document.immutableCharSequence
        if (declaration == null) return text.length
        val committed = PsiDocumentManager.getInstance(project).isCommitted(document)
        val spec = if (committed) GoDeclarationPsi.ofName(file.findElementAt(declaration.nameRange.startOffset)) as? GoTypeSpec else null
        val after = if (spec != null) {
            // a type of a `type (...)` group: after the group, not inside it (seen live: a method written between the specs)
            val methods = file.methods.filter { it.receiverTypeName == spec.name }
            (methods.map { it.textRange.endOffset } + (spec.parent?.textRange?.endOffset ?: spec.textRange.endOffset)).max()
        } else {
            val methods = structure.declarations.filter { it.kind == GoDeclarationKind.METHOD && it.receiver == declaration.name }
            val group = structure.groups.firstOrNull { it.contains(declaration.range) }
            (methods.map { it.range.endOffset } + (group?.endOffset ?: declaration.range.endOffset)).max()
        }
        val lineEnd = text.indexOf('\n', after).let { if (it < 0) text.length else it }
        return lineEnd.coerceAtMost(text.length)
    }

    /** Writes [code] after [declaration] with a blank line before it, then puts the caret on its first line. */
    fun insertAfter(declaration: GoDeclarationInfo?, code: String, title: String) {
        val at = insertionOffset(declaration)
        val text = document.immutableCharSequence
        // one blank line between the declaration and the code: none when the line at [at] is blank already, one when [at] begins a blank line
        val prefix = when {
            at == 0 -> ""
            text[at - 1] == '\n' -> if (at >= 2 && text[at - 2] == '\n') "" else "\n"
            else -> "\n\n"
        }
        // the code ends its last line; when a blank line follows [at] already, its own does not add a second one
        val blankLineFollows = text.startsWith("\n\n", at) || at == text.length - 1 && text[at] == '\n'
        val block = prefix + if (code.endsWith("\n") && blankLineFollows) code.dropLast(1) else code
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
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
            val spec = context.typeSpecAtCaret
            val struct = spec?.let(GoStructPsi::structOf) ?: return context.hint("Put the caret inside a struct type")
            val fields = GoStructPsi.fields(struct).filter { !it.embedded }
            if (fields.isEmpty()) return context.hint("${spec.name} has no named fields")
            val dialog = TagsDialog(context.project)
            if (!dialog.showAndGet()) return
            val kinds = dialog.kinds()
            if (kinds.isEmpty()) return
            val edits = tagEdits(fields, kinds, dialog.case(), dialog.omitEmpty())
            if (edits.isEmpty()) return context.hint("Every field has these tags already")
            WriteCommandAction.runWriteCommandAction(context.project, "Add Struct Tags", null, {
                // from the last field up: the offsets of the fields above stay right
                for ((range, text) in edits.sortedByDescending { it.first.startOffset }) context.document.replaceString(range.startOffset, range.endOffset, text)
                PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
            }, context.file)
        }

        /**
         * The edits that tag [fields] (of the PSI): the tag literal of a declaration replaced by the one with the missing keys, or a tag
         * written after the type of a declaration without one. A declaration of several names is tagged after its first one.
         */
        fun tagEdits(fields: List<GoStructPsi.Field>, kinds: List<String>, case: GoGenerators.TagCase, omitEmpty: Boolean): List<Pair<TextRange, String>> =
            fields.distinctBy { it.declaration }.mapNotNull { field ->
                val value = existingTagValue(field)
                // a backquote cannot go into the raw string the keys are written to
                if (value != null && '`' in value) return@mapNotNull null
                val tag = GoGenerators.tagFor(field.name, field.exported, value, kinds, case, omitEmpty) ?: return@mapNotNull null
                val existing = field.declaration.tag
                if (existing != null) existing.textRange to tag
                else {
                    val end = (field.declaration.type ?: field.declaration.anonymousFieldDefinition ?: return@mapNotNull null).textRange.endOffset
                    TextRange(end, end) to " $tag"
                }
            }

        /** The value of the tag of [field]; an interpreted string (`"json:\"id\""`) unquoted, as its keys are rewritten into a raw one. */
        private fun existingTagValue(field: GoStructPsi.Field): String? {
            val tag = field.tag ?: return null
            return if (tag.startsWith("\"")) StringUtil.unescapeStringCharacters(tag.removeSurrounding("\"")) else field.tagValue
        }
    }
}

/** Implement Interface: the popup of [GoInterfaceChooser] for the type at the caret. */
class GoImplementInterfaceAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.typeAtCaret != null
    override fun perform(context: GenerateContext) = implement(context)

    companion object {
        fun implement(context: GenerateContext) = GoInterfaceChooser.show(context)

        class Candidate(val declaration: GoDeclarationInfo, val fileName: String, val packageName: String?)

        /** Every interface with methods in the Go files of the project (the index knows names, not kinds): for the keyword templates. */
        fun interfaces(project: Project, current: GoFile): List<Candidate> {
            val result = ArrayList<Candidate>()
            for (file in com.intellij.psi.search.FileTypeIndex.getFiles(GoFileType, com.intellij.psi.search.GlobalSearchScope.projectScope(project))) {
                val psi = com.intellij.psi.PsiManager.getInstance(project).findFile(file) as? GoFile ?: continue
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
