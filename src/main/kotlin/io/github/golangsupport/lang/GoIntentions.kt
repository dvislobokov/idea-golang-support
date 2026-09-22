package io.github.golangsupport.lang

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import io.github.golangsupport.lint.GoErrcheckFixes
import io.github.golangsupport.lint.GoSignatureProvider

/** The line of the caret: its text, its bounds, its indent and the unit of indentation of the file. */
private class CaretLine(document: Document, offset: Int) {
    val number = document.getLineNumber(offset)
    val start = document.getLineStartOffset(number)
    val end = document.getLineEndOffset(number)
    val text: String = document.getText(TextRange(start, end))
    val indent = text.takeWhile { it == ' ' || it == '\t' }
    val unit = if (indent.startsWith(" ")) "    " else "\t"
}

private fun caretLine(editor: Editor?): CaretLine? = editor?.let { CaretLine(it.document, it.caretModel.offset) }

/**
 * Alt+Enter on a call that stands alone on its line (`os.Remove(path)`): the call with its error handled. How many values the call
 * returns is asked of gopls; without it the intention still offers the plain `if err := …` and says so. The same text as the errcheck
 * fix, offered without the linter.
 */
class GoHandleErrorIntention : IntentionAction {
    override fun getText(): String = "Handle error"
    override fun getFamilyName(): String = "Go: handle error"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile) return false
        val line = caretLine(editor) ?: return false
        return GoErrcheckFixes.callStatement(line.text) != null && !line.text.contains(":=") && !line.text.contains(" = ") && !line.text.trim().startsWith("return")
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val line = caretLine(editor) ?: return
        val virtualFile = file?.virtualFile ?: return
        val statement = GoErrcheckFixes.callStatement(line.text) ?: return
        val nameOffset = line.start + (GoErrcheckFixes.functionNameOffset(line.text) ?: return)
        val results = ProgressManager.getInstance().runProcessWithProgressSynchronously<Int?, RuntimeException>(
            { GoSignatureProvider.resultCount(project, virtualFile, nameOffset) }, "Asking gopls What the Function Returns", true, project,
        ) ?: 1
        if (results == 0) return HintManager.getInstance().showErrorHint(editor!!, "The function returns nothing")
        val replacement = line.indent + GoErrcheckFixes.handle(statement, results, line.indent, line.unit)
        WriteCommandAction.runWriteCommandAction(project, text, null, {
            editor!!.document.replaceString(line.start, line.end, replacement)
            replacement.indexOf("return err").takeIf { it >= 0 }?.let { editor.caretModel.moveToOffset(line.start + it + "return ".length) }
        }, file)
    }
}

/** Alt+Enter on `x, err := f()` (or `err = f()`) without a check below: `if err != nil { return … }` with the results of the function. */
class GoCheckErrorIntention : IntentionAction {
    override fun getText(): String = "Add if err != nil check"
    override fun getFamilyName(): String = "Go: check error"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile) return false
        val line = caretLine(editor) ?: return false
        val error = GoStatementsOfError.assignedError(line.text) ?: return false
        val document = editor!!.document
        val next = if (line.number + 1 < document.lineCount) document.getText(TextRange(document.getLineStartOffset(line.number + 1), document.getLineEndOffset(line.number + 1))).trim() else ""
        return !next.startsWith("if $error ")
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val line = caretLine(editor) ?: return
        val error = GoStatementsOfError.assignedError(line.text) ?: return
        val document = editor!!.document
        val returned = GoIdioms.returnStatement(document.immutableCharSequence, line.start, error)
        val check = "\n${line.indent}if $error != nil {\n${line.indent}${line.unit}$returned\n${line.indent}}"
        document.insertString(line.end, check)
        editor.caretModel.moveToOffset(line.end + check.length)
    }
}

object GoStatementsOfError {
    private val ASSIGNED = Regex("""^\s*(?:[\w.\[\]]+\s*,\s*)*(err\w*)\s*:?=\s*.+$""")

    /** `err` of `x, err := f()`; null for a line that assigns no error. */
    fun assignedError(line: String): String? = ASSIGNED.matchEntire(line)?.groupValues?.get(1)?.takeIf { !line.trim().startsWith("if ") && !line.trim().startsWith("for ") }
}

/** Alt+Enter inside a function that returns values and ends without `return`: `return 0, nil` before its closing brace. */
class GoAddMissingReturnIntention : IntentionAction {
    override fun getText(): String = "Add missing return"
    override fun getFamilyName(): String = "Go: missing return"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile || editor == null) return false
        val function = functionAt(editor) ?: return false
        return GoGenerators.returnStatement(function.signature) != null && lastStatement(editor.document, function)?.startsWith("return") == false
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val function = functionAt(editor ?: return) ?: return
        val body = function.body ?: return
        val statement = GoGenerators.returnStatement(function.signature) ?: return
        val document = editor.document
        val closingLine = document.getLineNumber(body.endOffset - 1)
        val closingStart = document.getLineStartOffset(closingLine)
        val indent = document.getText(TextRange(closingStart, body.endOffset - 1)).takeWhile { it == ' ' || it == '\t' }
        val unit = if (indent.startsWith(" ")) "    " else "\t"
        val inserted = "$indent$unit$statement\n"
        document.insertString(closingStart, inserted)
        editor.caretModel.moveToOffset(closingStart + inserted.length - 1)
    }

    private fun functionAt(editor: Editor): GoDeclarationInfo? {
        val offset = editor.caretModel.offset
        return GoDeclarations.scan(editor.document.immutableCharSequence).declarations
            .filter { (it.kind == GoDeclarationKind.FUNCTION || it.kind == GoDeclarationKind.METHOD) && it.body != null }
            .lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }
    }

    /** The last line of code of the body, without its indent; null for an empty body. */
    private fun lastStatement(document: Document, function: GoDeclarationInfo): String? {
        val body = function.body ?: return null
        val text = document.getText(TextRange(body.startOffset + 1, body.endOffset - 1))
        return text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("//") }?.let { if (it == "}" ) "}" else it } ?: ""
    }
}

/**
 * Alt+Enter on a call of a function that is not there (`undefined: total`, as gopls says): the function with parameters from the
 * arguments, at the end of the file; a method when the call is `s.name(...)` on a receiver of this file. The types of the parameters are
 * a guess (`any` for anything but a literal) and the first thing to change.
 */
class GoCreateFunctionIntention : IntentionAction {
    private var name: String = ""

    override fun getText(): String = if (name.isEmpty()) "Create function" else "Create function '$name'"
    override fun getFamilyName(): String = "Go: create function"
    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is GoFile || editor == null) return false
        val text = editor.document.immutableCharSequence
        val (called, qualifier) = GoGenerators.calledName(text, editor.caretModel.offset) ?: return false
        name = called
        val structure = GoDeclarations.scan(text)
        // a function of this file, a builtin, a package (`fmt.Println`): not ours to create
        if (called in BUILTINS || structure.declarations.any { it.name == called && (qualifier == null && it.kind == GoDeclarationKind.FUNCTION || qualifier != null && it.kind == GoDeclarationKind.METHOD) }) return false
        if (qualifier != null && structure.imports.any { (it.alias ?: it.path.substringAfterLast('/')) == qualifier }) return false
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor!!.document
        val text = document.immutableCharSequence
        val offset = editor.caretModel.offset
        val (called, qualifier) = GoGenerators.calledName(text, offset) ?: return
        var nameStart = offset
        while (nameStart > 0 && (text[nameStart - 1].isLetterOrDigit() || text[nameStart - 1] == '_')) nameStart--
        val call = GoGenerators.callText(text, nameStart) ?: return
        val structure = GoDeclarations.scan(text)
        // `s.total(...)` inside a method with the receiver `s`: a method of that type
        val receiverType = qualifier?.let { q ->
            structure.declarations.filter { it.kind == GoDeclarationKind.METHOD }.lastOrNull { offset >= it.range.startOffset && offset <= it.range.endOffset }
                ?.takeIf { document.getText(it.range).substringBefore(')').substringAfter('(').trim().substringBefore(' ') == q }?.receiver
        }
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
