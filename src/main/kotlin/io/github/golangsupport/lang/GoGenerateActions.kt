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
import com.intellij.psi.PsiElement
import com.intellij.openapi.project.DumbService
import io.github.golangsupport.ide.intentions.GoEnumConstants
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.semantic.types.GoStructType as SemanticStructType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeSpec
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.columns
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
    val offset: Int get() = editor.caretModel.offset
    val packageName: String? get() = file.packageName

    /** The top-level type spec the caret stands in, or on the keyword or the name of, from the PSI. Needs read access. */
    val typeSpecAtCaret: GoTypeSpec? get() = GoStructPsi.typeSpecAt(file, offset)

    /** [typeSpecAtCaret] as a value for the generators ([GoStructPsi.infoOf]). */
    val typeAtCaret: GoDeclarationInfo? get() = typeSpecAtCaret?.let(GoStructPsi::infoOf)
    val structAtCaret: GoDeclarationInfo? get() = typeAtCaret?.takeIf { it.kind == GoDeclarationKind.STRUCT }

    /** The struct type at the caret with its fields from the PSI. */
    val structTypeAtCaret: GoStructType? get() = typeSpecAtCaret?.let(GoStructPsi::structOf)
    /** The top-level function or method the caret is in (its doc comment included), from the PSI. Needs read access. */
    val functionAtCaret: GoDeclarationInfo?
        get() {
            for (at in intArrayOf(offset, offset - 1)) {
                var element: PsiElement? = file.findElementAt(at.coerceAtLeast(0))
                while (element != null && element.parent !is GoFile) element = element.parent
                (element as? GoFunctionOrMethodDeclaration)?.let { return GoDeclarationInfo.of(it) }
            }
            return null
        }

    /** The declaration the generated code goes after: the one at the caret, and the last method of its type after that. Committed document. */
    fun insertionOffset(declaration: GoDeclarationInfo?): Int {
        val text = document.immutableCharSequence
        if (declaration == null) return text.length
        val element = GoDeclarationKind.ofName(file.findElementAt(declaration.nameRange.startOffset))
        val after = if (element is GoTypeSpec) {
            // a type of a `type (...)` group: after the group, not inside it (seen live: a method written between the specs)
            val methods = file.methods.filter { it.receiverTypeName == element.name }
            (methods.map { it.textRange.endOffset } + (element.parent?.textRange?.endOffset ?: element.textRange.endOffset)).max()
        } else {
            element?.textRange?.endOffset ?: declaration.range.endOffset
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

/** The struct at the caret with the fields chosen in a dialog; [title] names the dialog, [ticked] the fields ticked at the start. */
private fun GenerateContext.chooseFields(title: String, ticked: (GoDeclarationInfo) -> Boolean = { true }): Pair<GoDeclarationInfo, List<GoDeclarationInfo>>? {
    val struct = structAtCaret ?: run { hint("Put the caret inside a struct type"); return null }
    val fields = GoGenerators.fields(struct)
    if (fields.isEmpty()) { hint("${struct.name} has no fields"); return null }
    val dialog = FieldsDialog(project, title, struct.name, fields, ticked)
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

/**
 * `String()` of an enum (`type Color int` with its constants in the package), in the shape `stringer` writes: offered on an integer type
 * that has constants, is no set of bit flags and has no `String()` yet.
 */
class GoGenerateEnumStringAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean =
        !DumbService.isDumb(context.project) && context.typeAtCaret?.kind == GoDeclarationKind.TYPE && context.typeSpecAtCaret?.let(::code) != null

    override fun perform(context: GenerateContext) {
        val type = context.typeAtCaret ?: return context.hint("Put the caret on a type declaration")
        val code = context.typeSpecAtCaret?.let(::code) ?: return context.hint("${type.name} is no enum with constants, or has String() already")
        WriteCommandAction.runWriteCommandAction(context.project, "Generate String() for Enum", null, {
            context.insertAfter(type, code, "Generate String() for Enum")
            // the import goes above: the caret, put on the method already, moves with the text
            GoImports.add(context.document.immutableCharSequence, "fmt")?.let { context.document.insertString(it.offset, it.text) }
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
        }, context.file)
    }

    companion object {
        /** The members `String()` names: the constants of [spec] in its package, one per value (the first name wins). Read action, smart mode. */
        fun members(spec: GoTypeSpec): List<GoEnumConstants.Member>? {
            val type = GoSemanticService.getInstance(spec.project).declarationType(spec) as? GoNamedType ?: return null
            if (spec.typeParameters != null || (type.underlying() as? GoBasicType)?.kind?.isInteger != true) return null
            val members = GoEnumConstants.of(type, ownPackage = true) ?: return null
            if (GoEnumConstants.isFlags(members.map { it.value })) return null
            return GoEnumConstants.distinct(members)
        }

        /** The method for [spec]; null when it is no enum or has a `String` method already. */
        fun code(spec: GoTypeSpec): String? {
            val type = GoSemanticService.getInstance(spec.project).declarationType(spec) as? GoNamedType ?: return null
            if (type.methods.any { it.name == "String" }) return null
            val members = members(spec) ?: return null
            val underlying = (type.underlying() as? GoBasicType)?.name ?: return null
            return GoGenerators.enumStringMethod(type.name, receiverOf(type) ?: GoGenerators.receiverName(type.name), members.map { it.name }, underlying)
        }

        /** The receiver name the methods of [type] use already: a new method keeps it (staticcheck ST1016). */
        fun receiverOf(type: GoNamedType): String? =
            type.methods.firstNotNullOfOrNull { (it.declaration as? GoMethodDeclaration)?.receiver?.name?.takeIf { name -> name != "_" } }
    }
}

/**
 * `Equal(other T) bool` for the struct at the caret: `==` for comparable fields, `bytes.Equal`, `slices.Equal`, `maps.Equal`,
 * `time.Time.Equal`; a field compared otherwise only by `reflect.DeepEqual` is not ticked at the start.
 */
class GoGenerateEqualAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean {
        if (context.structAtCaret == null) return false
        if (DumbService.isDumb(context.project)) return true
        val type = context.typeSpecAtCaret?.let { GoSemanticService.getInstance(it.project).declarationType(it) } as? GoNamedType
        return type?.methods?.none { it.name == "Equal" } ?: true
    }

    override fun perform(context: GenerateContext) {
        val spec = context.typeSpecAtCaret ?: return context.hint("Put the caret inside a struct type")
        val kinds = kinds(spec)
        val (struct, fields) = context.chooseFields("Equal Method") { kinds[it.name]?.byDefault ?: true } ?: return
        val type = GoSemanticService.getInstance(spec.project).declarationType(spec) as? GoNamedType
        val chosen = fields.map { it.name to (kinds[it.name] ?: GoGenerators.EqualKind.OPERATOR) }
        val receiver = type?.let(GoGenerateEnumStringAction::receiverOf) ?: GoGenerators.receiverName(struct.name)
        // the receiver form of the methods there are; a value one when there are none: Equal does not change the value
        val code = GoGenerators.equalMethod(struct.name, receiver, type?.methods.orEmpty().any { it.pointerReceiver }, chosen)
        WriteCommandAction.runWriteCommandAction(context.project, "Generate Equal Method", null, {
            context.insertAfter(struct, code, "Generate Equal Method")
            for (path in GoGenerators.equalImports(chosen.map { it.second })) {
                GoImports.add(context.document.immutableCharSequence, path)?.let { context.document.insertString(it.offset, it.text) }
            }
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
        }, context.file)
    }

    companion object {
        /** How each named field of the struct of [spec] is compared, by field name. Read action. */
        fun kinds(spec: GoTypeSpec): Map<String, GoGenerators.EqualKind> {
            val struct = GoSemanticService.getInstance(spec.project).declarationType(spec).underlying() as? SemanticStructType ?: return emptyMap()
            return struct.fields.filter { !it.embedded }.associate { it.name to kindOf(it.type) }
        }

        fun kindOf(type: GoType): GoGenerators.EqualKind {
            if (type is GoNamedType && type.name == "Time" && type.pkgPath == "time") return GoGenerators.EqualKind.TIME
            return when (val underlying = type.underlying()) {
                is GoSliceType -> when {
                    (underlying.elem as? GoBasicType)?.kind == GoBasicKind.UINT8 -> GoGenerators.EqualKind.BYTES
                    GoTypePredicates.comparable(underlying.elem) -> GoGenerators.EqualKind.SLICES
                    else -> GoGenerators.EqualKind.DEEP
                }
                is GoMapType -> if (GoTypePredicates.comparable(underlying.value)) GoGenerators.EqualKind.MAPS else GoGenerators.EqualKind.DEEP
                // a type the checker does not know: `==` is the likeliest to be right
                GoUnknownType -> GoGenerators.EqualKind.OPERATOR
                else -> if (GoTypePredicates.comparable(type)) GoGenerators.EqualKind.OPERATOR else GoGenerators.EqualKind.DEEP
            }
        }
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
            tagEdits(fields, kinds, case.apply, omitEmpty)

        /** [tagEdits] with the names written by [naming] (the style the struct uses already: Add Tag Key to All Fields of completion). */
        fun tagEdits(fields: List<GoStructPsi.Field>, kinds: List<String>, naming: (String) -> String, omitEmpty: Boolean): List<Pair<TextRange, String>> =
            fields.distinctBy { it.declaration }.mapNotNull { field ->
                val value = existingTagValue(field)
                // a backquote cannot go into the raw string the keys are written to
                if (value != null && '`' in value) return@mapNotNull null
                val tag = GoGenerators.tagFor(field.name, field.exported, value, kinds, naming, omitEmpty) ?: return@mapNotNull null
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
    }
}

/** A table-driven test for the function at the caret, in `<file>_test.go` next to it (made when it is not there). */
class GoGenerateTestAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.functionAtCaret != null && !context.file.isTestFile
    override fun perform(context: GenerateContext) = generate(context)

    companion object {
        fun generate(context: GenerateContext) {
            PsiDocumentManager.getInstance(context.project).commitDocument(context.document)
            val function = context.functionAtCaret ?: return context.hint("Put the caret inside a function")
            val source = context.file.virtualFile ?: return
            val packageName = context.packageName
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

/**
 * Generate | Method: a method of the type at the caret from a dialog (name, pointer receiver, parameters, results), written after the
 * type's last method with the receiver name its methods use and a body that panics.
 */
class GoGenerateMethodAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.typeAtCaret?.let { it.kind != GoDeclarationKind.INTERFACE } == true

    override fun perform(context: GenerateContext) {
        val type = context.typeAtCaret ?: return context.hint("Put the caret inside a type declaration")
        val spec = context.typeSpecAtCaret
        val named = if (spec == null || DumbService.isDumb(context.project)) null else GoSemanticService.getInstance(context.project).declarationType(spec) as? GoNamedType
        val receiver = named?.let(GoGenerateEnumStringAction::receiverOf) ?: GoGenerators.receiverName(type.name)
        val pointer = spec?.takeIf { named != null }?.let(GoInterfaceChooser::pointerReceiver) ?: (type.kind == GoDeclarationKind.STRUCT)
        val input = dialog?.invoke(type.name, pointer) ?: MethodDialog(context.project, type.name, pointer).takeIf { it.showAndGet() }?.input() ?: return
        if (!Regex("""[\p{L}_][\p{L}\p{Nd}_]*""").matches(input.name) || input.name in GoNames.KEYWORDS) return context.hint("'${input.name}' is not a valid method name")
        context.insertAfter(type, GoGenerators.method(type.name, receiver, input.pointer, input.name, input.parameters, input.results), "Generate Method")
    }

    /** What the dialog answers. */
    class Input(val name: String, val pointer: Boolean, val parameters: String, val results: String)

    companion object {
        /** The dialog in tests: the type name and the pointer default in, the answer out (null: cancelled). */
        @org.jetbrains.annotations.TestOnly
        @JvmStatic
        var dialog: ((String, Boolean) -> Input?)? = null
    }
}

private class MethodDialog(project: Project, typeName: String, pointer: Boolean) : DialogWrapper(project) {
    private val name = com.intellij.ui.components.JBTextField("newMethod")
    private val pointer = JBCheckBox("Pointer receiver (*$typeName)", pointer)
    private val parameters = com.intellij.ui.components.JBTextField()
    private val results = com.intellij.ui.components.JBTextField()

    init {
        title = "Generate Method of $typeName"
        init()
    }

    fun input(): GoGenerateMethodAction.Input = GoGenerateMethodAction.Input(name.text.trim(), pointer.isSelected, parameters.text.trim(), results.text.trim())

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(name).columns(30) }
        row { cell(pointer) }
        row("Parameters:") { cell(parameters).columns(30).comment("ctx context.Context, id string") }
        row("Results:") { cell(results).columns(30).comment("Item, error") }
    }

    override fun getPreferredFocusedComponent(): JComponent = name
}

/**
 * Generate | Tests for Package: a table-driven test ([GoGenerators.testFunction]) for every exported function and method of the package
 * of the file that has none yet, each in `<file>_test.go` next to its file (made when it is not there); the candidates are ticked in a
 * dialog first.
 */
class GoGenerateTestsForPackageAction : GoGenerateAction() {
    override fun isAvailable(context: GenerateContext): Boolean = context.file.virtualFile?.parent != null

    override fun perform(context: GenerateContext) {
        val candidates = candidates(context.file)
        if (candidates.isEmpty()) return context.hint("Every exported function of the package has a test")
        val chosen = chooser?.invoke(candidates) ?: TestsDialog(context.project, candidates).takeIf { it.showAndGet() }?.chosen() ?: return
        if (chosen.isEmpty()) return
        write(context.project, chosen)
    }

    /** A function without a test: [function] declared in [file]. */
    class Candidate(val file: GoFile, val function: GoDeclarationInfo) {
        override fun toString(): String = "${function.presentation}  ${file.name}"
    }

    companion object {
        /** The dialog in tests: the candidates in, the ticked ones out. */
        @org.jetbrains.annotations.TestOnly
        @JvmStatic
        var chooser: ((List<Candidate>) -> List<Candidate>)? = null

        /** The functions of the package of [file] without a test in its `_test.go` files, by file and position. Read action, committed documents. */
        fun candidates(file: GoFile): List<Candidate> {
            val directory = file.containingDirectory ?: file.originalFile.containingDirectory ?: return emptyList()
            val goFiles = directory.files.filterIsInstance<GoFile>()
            val tests = goFiles.filter { it.isTestFile }.map { it.text }
            return goFiles.filter { !it.isTestFile }.sortedBy { it.name }.flatMap { source ->
                GoGenerators.untested(GoDeclarationInfo.topLevel(source), tests).map { Candidate(source, it) }
            }
        }

        /** The tests of [chosen], appended to the `_test.go` file of each one's file, in one command. */
        fun write(project: Project, chosen: List<Candidate>) {
            WriteCommandAction.runWriteCommandAction(project, "Generate Tests for Package", null, {
                for ((source, functions) in chosen.groupBy { it.file }) {
                    val vf = source.virtualFile ?: continue
                    val testName = vf.nameWithoutExtension + "_test.go"
                    val packageName = source.packageName ?: "main"
                    val target = vf.parent.findChild(testName) ?: vf.parent.createChildData(this, testName).also { VfsUtil.saveText(it, "package $packageName\n") }
                    val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(target) ?: continue
                    val tests = functions.joinToString("\n") { GoGenerators.testFunction(it.function, packageName) }
                    val text = document.text
                    document.insertString(text.length, (if (text.endsWith("\n\n") || text.isEmpty()) "" else if (text.endsWith("\n")) "\n" else "\n\n") + tests)
                    GoImports.add(document.immutableCharSequence, "testing")?.let { document.insertString(it.offset, it.text) }
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            })
        }
    }
}

private class TestsDialog(project: Project, private val candidates: List<GoGenerateTestsForPackageAction.Candidate>) : DialogWrapper(project) {
    private val list = CheckBoxList<GoGenerateTestsForPackageAction.Candidate>()

    init {
        title = "Generate Tests for Package"
        candidates.forEach { list.addItem(it, it.toString(), true) }
        init()
    }

    fun chosen(): List<GoGenerateTestsForPackageAction.Candidate> = candidates.filter { list.isItemSelected(it) }

    override fun createCenterPanel(): JComponent = com.intellij.ui.ScrollPaneFactory.createScrollPane(list).apply {
        preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(480), com.intellij.util.ui.JBUI.scale(300))
    }

    override fun getPreferredFocusedComponent(): JComponent = list
}

/** The fields to generate for: all ticked at the start. */
private class FieldsDialog(
    project: Project, title: String, typeName: String, private val fields: List<GoDeclarationInfo>, ticked: (GoDeclarationInfo) -> Boolean = { true },
) : DialogWrapper(project) {
    private val list = CheckBoxList<GoDeclarationInfo>()

    init {
        this.title = "$title of $typeName"
        fields.forEach { list.addItem(it, "${it.name} ${it.signature}", ticked(it)) }
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
