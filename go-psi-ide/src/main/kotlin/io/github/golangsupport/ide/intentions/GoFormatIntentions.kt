package io.github.golangsupport.ide.intentions

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.printf.GoFormatString
import io.github.golangsupport.ide.inspections.printf.GoPrintfCall
import io.github.golangsupport.ide.inspections.printf.GoPrintfCalls
import io.github.golangsupport.ide.inspections.printf.GoPrintfFunctions
import io.github.golangsupport.ide.inspections.printf.GoStringValue
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral

/** The call around the caret for the printf intentions: the innermost one, not beyond a function literal's body. */
internal object GoFormatText {
    fun callAt(file: GoFile, offset: Int): GoCallExpr? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, GoCallExpr::class.java, false, io.github.golangsupport.lang.psi.GoBlock::class.java)
    }

    fun printfAt(file: GoFile, offset: Int): GoPrintfCall? = callAt(file, offset)?.let(GoPrintfCalls::of)

    fun available(project: Project, editor: Editor?, file: PsiFile?): GoFile? =
        (file as? GoFile)?.takeIf { editor != null && GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project) }
}

/**
 * Add format string argument (GoLand's text): with the caret inside the format string of a printf-like call, asks for an expression,
 * inserts `%v` at the caret and passes the expression as the argument of that verb (after the arguments of the verbs before the caret).
 * When a verb of the format has no argument yet (`Sprintf("%d %s", n)`), anywhere in the format: the expression becomes the argument of
 * the first such verb, no verb is added. Not offered in a format with explicit argument indexes (`%[2]d`), a malformed one, or a call
 * spreading its arguments (`args...`).
 */
class GoAddFormatStringArgumentIntention : IntentionAction {
    override fun getText(): String = "Add format string argument"

    override fun getFamilyName(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val go = GoFormatText.available(project, editor, file) ?: return false
        return insertion(go, editor!!.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val go = file as? GoFile ?: return
        if (editor == null || insertion(go, editor.caretModel.offset) == null) return
        val expression = Messages.showInputDialog(project, "Expression:", "Add Format String Argument", null)?.trim()
        if (expression.isNullOrEmpty()) return
        WriteCommandAction.runWriteCommandAction(project, text, null, {
            val at = insertion(go, editor.caretModel.offset) ?: return@runWriteCommandAction
            val argument = GoEditPlan.Edit(at.argOffset, at.argOffset, at.argText(expression))
            if (!at.newVerb) {
                GoEditText.apply(go, listOf(argument))
            } else {
                GoEditText.apply(go, listOf(GoEditPlan.Edit(at.verbOffset, at.verbOffset, "%v"), argument))
                editor.caretModel.moveToOffset(at.verbOffset + 2)
            }
        }, go)
    }

    /**
     * Where `%v` goes ([verbOffset]; -1 when the format has a verb without an argument, which gets it instead) and where the argument goes
     * ([argOffset]: before an argument, or after the last one).
     */
    class Insertion(val verbOffset: Int, val argOffset: Int, private val before: Boolean) {
        val newVerb: Boolean get() = verbOffset >= 0

        fun argText(expression: String): String = if (before) "$expression, " else ", $expression"
    }

    companion object {
        fun insertion(file: GoFile, offset: Int): Insertion? {
            val call = GoFormatText.printfAt(file, offset) ?: return null
            if (!call.isPrintf || call.spread) return null
            val literal = call.format as? GoStringLiteral ?: return null
            val rel = offset - literal.textRange.startOffset
            if (rel <= 0 || rel >= literal.textLength) return null
            val value = GoStringValue.decode(literal.text) ?: return null
            if (value.closingQuote != literal.textLength - 1) return null
            // the decoded index at the caret: the caret must stand before a whole char (not inside an escape) or before the closing quote
            val index = if (rel == value.closingQuote) value.value.length else (0 until value.value.length).firstOrNull { value.sourceRange(it, it + 1)?.first == rel } ?: return null
            val format = GoFormatString.parse(value.value)
            if (format.error != null || format.anyIndex) return null
            val values = call.values
            // a verb without its argument (`Sprintf("%d %s", n)`, GoLand offers it there, seen live): its argument goes after the last one
            if (format.directives.sumOf { it.argNums.size } > values.size) return Insertion(-1, (values.lastOrNull() ?: literal).textRange.endOffset, false)
            if (format.directives.any { index > it.start && index < it.end }) return null
            val before = format.directives.filter { it.end <= index }.sumOf { it.argNums.size }
            val next = values.getOrNull(before)
            if (next != null) return Insertion(offset, next.textRange.startOffset, true)
            val last = values.lastOrNull() ?: literal
            return Insertion(offset, last.textRange.endOffset, false)
        }
    }
}

/** Base of the two intentions that edit [GoPrintfFunctions]: no document change, so no preview; the highlighting is restarted. */
abstract class GoPrintfFunctionIntention : IntentionAction, LowPriorityAction {
    override fun getFamilyName(): String = text

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    /** The name to store for the call at [offset], or null when the intention does not apply there. */
    protected abstract fun nameAt(file: GoFile, offset: Int): String?

    protected abstract fun update(functions: GoPrintfFunctions, name: String)

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val go = GoFormatText.available(project, editor, file) ?: return false
        return nameAt(go, editor!!.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val go = file as? GoFile ?: return
        val name = nameAt(go, editor?.caretModel?.offset ?: return) ?: return
        update(GoPrintfFunctions.getInstance(), name)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}

/** Exclude string formatting function: the printf-like function called at the caret is no longer checked as one (GoPrintfFunctions). */
class GoExcludeStringFormattingFunctionIntention : GoPrintfFunctionIntention() {
    override fun getText(): String = "Exclude string formatting function"

    override fun nameAt(file: GoFile, offset: Int): String? = GoFormatText.printfAt(file, offset)?.name

    override fun update(functions: GoPrintfFunctions, name: String) = functions.exclude(name)
}

/** Mark as string formatting function: a function ending in `...any` called at the caret is checked as printf-like (GoPrintfFunctions). */
class GoMarkStringFormattingFunctionIntention : GoPrintfFunctionIntention() {
    override fun getText(): String = "Mark as string formatting function"

    override fun nameAt(file: GoFile, offset: Int): String? {
        val call = GoFormatText.callAt(file, offset) ?: return null
        return if (GoPrintfCalls.markable(call)) GoPrintfCalls.nameOf(call) else null
    }

    override fun update(functions: GoPrintfFunctions, name: String) = functions.mark(name)
}
