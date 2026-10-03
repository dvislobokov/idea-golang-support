package io.github.golangsupport.lang

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration

/**
 * Alt+Enter on a call of a function that is not there (`undefined: total`, as gopls says): the function with parameters from the
 * arguments, at the end of the file; a method when the call is `s.name(...)` on a receiver of this file. The types of the parameters are
 * a guess (`any` for anything but a literal) and the first thing to change.
 */
/**
 * Create function: the quick fix of gopls for `undefined: name` writes it with the real types of the arguments and of the assignment,
 * and the platform shows that fix under Alt+Enter with the diagnostic (`Create function name`). This one, with the types guessed
 * from the text (`any`), is for when there is no such fix: no server, or a call the server does not see as one.
 */
class GoCreateFunctionIntention : IntentionAction {
    private var name: String = ""

    override fun getText(): String = if (name.isEmpty()) "Create function" else "Create function '$name'"
    override fun getFamilyName(): String = "Go: create function"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile || editor == null) return false
        // Built-in code actions: the typed Create function of go-psi-ide answers instead
        if (GoFeatures.native(GoFeature.CODE_ACTIONS, project)) return false
        val text = editor.document.immutableCharSequence
        val (called, qualifier) = GoGenerators.calledName(text, editor.caretModel.offset) ?: return false
        name = called
        // a function of this file, a builtin, a package (`fmt.Println`): not ours to create
        if (called in BUILTINS || (if (qualifier == null) file.functions.any { it.name == called } else file.methods.any { it.name == called })) return false
        if (qualifier != null && file.imports.any { (it.alias ?: it.path.substringAfterLast('/')) == qualifier }) return false
        return !hasServerFix(project, editor, called)
    }

    /** Whether the fix of the language server for this call is in the list already: a quick fix of an error at the caret with the same name. */
    private fun hasServerFix(project: Project, editor: Editor, called: String): Boolean {
        val offset = editor.caretModel.offset
        val wanted = "create function $called"
        return DaemonCodeAnalyzerImpl.getHighlights(editor.document, HighlightSeverity.ERROR, project)
            .filter { offset >= it.startOffset && offset <= it.endOffset }
            .any { info -> info.findRegisteredQuickFix<Boolean> { descriptor, _ -> true.takeIf { descriptor.action.text.trim().equals(wanted, ignoreCase = true) } } == true }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor!!.document
        val text = document.immutableCharSequence
        val offset = editor.caretModel.offset
        val (called, qualifier) = GoGenerators.calledName(text, offset) ?: return
        PsiDocumentManager.getInstance(project).commitDocument(document)
        // `s.total(...)` inside a method with the receiver `s`: a method of that type
        val receiverType = qualifier?.let { q ->
            PsiTreeUtil.getParentOfType(file?.findElementAt(offset), GoMethodDeclaration::class.java)?.takeIf { it.receiver?.name == q }?.receiverTypeName
        }
        WriteCommandAction.runWriteCommandAction(project, "Create Function", null, { writeOwn(document, text, offset, called, qualifier, receiverType, editor) }, file)
    }

    private fun writeOwn(document: Document, text: CharSequence, offset: Int, called: String, qualifier: String?, receiverType: String?, editor: Editor) {
        var nameStart = offset
        while (nameStart > 0 && (text[nameStart - 1].isLetterOrDigit() || text[nameStart - 1] == '_')) nameStart--
        val call = GoGenerators.callText(text, nameStart) ?: return
        val lineStart = text.lastIndexOf('\n', nameStart - 1) + 1
        val qualifierLength = qualifier?.let { it.length + 1 } ?: 0
        val assigned = GoGenerators.assignedNames(text.substring(lineStart, (nameStart - qualifierLength).coerceAtLeast(lineStart)))
        val code = GoGenerators.functionFromCall(called, GoGenerators.callArguments(call), receiverType, assigned)
        val end = text.length
        val prefix = if (text.endsWith("\n\n")) "" else if (text.endsWith("\n")) "\n" else "\n\n"
        document.insertString(end, prefix + code)
        editor.caretModel.moveToOffset(end + prefix.length + code.indexOf("panic"))
    }

    private companion object {
        val BUILTINS = setOf("append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make", "max", "min", "new", "panic", "print", "println", "real", "recover",
            "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "float32", "float64", "bool", "byte", "rune", "error", "any")
    }
}

/** Alt+Enter inside a struct: the tags dialog of Alt+Insert. */
class GoAddStructTagsIntention : IntentionAction {
    override fun getText(): String = "Add struct tags..."
    override fun getFamilyName(): String = "Go: struct tags"
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is GoFile && editor != null && GenerateContext(project, editor, file).structAtCaret != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = GoGenerateStructTagsAction.addTags(GenerateContext(project, editor!!, file as GoFile))
}

/** Alt+Enter inside a type: Implement Interface of Alt+Insert. */
class GoImplementInterfaceIntention : IntentionAction {
    override fun getText(): String = "Implement interface..."
    override fun getFamilyName(): String = "Go: implement interface"
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is GoFile && editor != null && GenerateContext(project, editor, file).typeAtCaret != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = GoImplementInterfaceAction.implement(GenerateContext(project, editor!!, file as GoFile))
}

/** Alt+Enter inside a function of a non-test file: a table-driven test for it. */
class GoGenerateTestIntention : IntentionAction {
    override fun getText(): String = "Generate test"
    override fun getFamilyName(): String = "Go: generate test"
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file is GoFile && !file.isTestFile && editor != null && GenerateContext(project, editor, file).functionAtCaret != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = GoGenerateTestAction.generate(GenerateContext(project, editor!!, file as GoFile))
}

/**
 * Alt+Enter inside a struct the compiler pads more than it has to: the fields in the order that wastes the least ([GoFieldAlignment]),
 * what `fieldalignment` of `go vet` asks for. Also the fix of that finding of golangci-lint, for the struct around the line it names.
 */
class GoReorderFieldsIntention(private val line: Int? = null) : IntentionAction {
    private var analysis: Pair<GoDeclarationInfo, GoFieldAlignment.Result>? = null

    override fun getText(): String = analysis?.let { (_, result) -> "Reorder fields for a smaller struct (${result.currentSize} → ${result.optimalSize} bytes)" } ?: "Reorder fields for a smaller struct"
    override fun getFamilyName(): String = "Go: reorder struct fields"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile || editor == null) return false
        analysis = analyze(GenerateContext(project, editor, file))
        return analysis != null
    }

    private fun analyze(context: GenerateContext): Pair<GoDeclarationInfo, GoFieldAlignment.Result>? {
        // the struct and its sizes from the PSI; nothing while the document is not committed
        if (!PsiDocumentManager.getInstance(context.project).isCommitted(context.document)) return null
        val spec = if (line == null) context.typeSpecAtCaret else {
            if (line !in 0 until context.document.lineCount) return null
            GoStructPsi.structSpecAt(context.file, context.document.getLineStartOffset(line))
        } ?: return null
        val struct = GoStructPsi.infoOf(spec) ?: return null
        if (struct.body == null) return null
        val result = GoFieldAlignment.analyze(GoStructPsi.structOf(spec) ?: return null) ?: return null
        return (struct to result).takeIf { result.saves }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is GoFile || editor == null) return
        val (struct, result) = analyze(GenerateContext(project, editor, file)) ?: return
        val body = struct.body ?: return
        val document = editor.document
        document.replaceString(body.startOffset + 1, body.endOffset - 1, GoFieldAlignment.rewrite(document.immutableCharSequence.subSequence(body.startOffset + 1, body.endOffset - 1), result))
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
