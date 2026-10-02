package io.github.golangsupport.lsp

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoCompletionOrder
import io.github.golangsupport.lang.GoExpectedTypes
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoSnippets
import io.github.golangsupport.lang.GoStructLiterals
import io.github.golangsupport.settings.GoSettings
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position

/**
 * The list of gopls, as the platform makes it, with what the plugin knows on top:
 * - names that begin with what is typed go first ([GoCompletionOrder]); the weigher of the platform, which keeps the order of the
 *   server, comes after the priority of an item, so among equals the order of the server stays;
 * - values of the type the code wants are bold and go above the others of their group; smart completion (Ctrl+Shift+Space) leaves
 *   only them ([GoExpectedTypes]);
 * - a completed call shows its parameters and opens the list for the first of them ([GoplsCallInsertHandler]).
 */
class GoplsCompletionSupport : LspCompletionSupport() {
    /** The type wanted at a place of a document: asked once for a list, which is made of hundreds of items. */
    private class Expected(val stamp: Long, val offset: Int, val path: String, val type: String?)

    @Volatile private var expected: Expected? = null

    /** Not when the plugin's PSI is the source of completion; while the IDE indexes the PSI stands down and the server answers. */
    override fun shouldRunCodeCompletion(parameters: CompletionParameters): Boolean =
        !GoFeatures.native(GoFeature.COMPLETION, parameters.originalFile.project) && super.shouldRunCodeCompletion(parameters)

    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        if (item.insertTextFormat == InsertTextFormat.Snippet) {
            item.insertText = item.insertText?.let(GoSnippets::unescape)
            item.textEdit?.let { edit -> if (edit.isLeft) edit.left.newText = GoSnippets.unescape(edit.left.newText) else edit.right.newText = GoSnippets.unescape(edit.right.newText) }
        }
        val element = super.createLookupElement(parameters, item) ?: return null
        val settings = GoSettings.getInstance()
        val name = item.filterText ?: item.label
        val text = parameters.editor.document.immutableCharSequence
        var priority = if (settings.completionPrefixFirst) GoCompletionOrder.priority(GoCompletionOrder.typed(text, parameters.offset), name) else 0.0
        var result = element
        if (settings.completionByType) {
            val fits = expectedType(parameters)?.let { GoExpectedTypes.fits(it, item.label, item.detail, isCall(item)) }
            if (fits == false && parameters.completionType == CompletionType.SMART) return null
            if (fits == true) {
                priority += GoCompletionOrder.FITS
                result = Bold(result)
            }
        }
        if (settings.completionArguments && isCall(item) && hasParameters(item)) result = WithArguments(result)
        if (settings.completionStructBraces && !isCall(item)) {
            // a type of a package that is not imported comes as `type (from "net/http")`: whether it is a struct is asked after it is written
            if (item.kind == CompletionItemKind.Struct) result = WithBraces(result, true) else if (item.detail?.startsWith("type") == true) result = WithBraces(result, false)
        }
        return if (priority == 0.0) result else PrioritizedLookupElement.withPriority(result, priority)
    }

    private fun expectedType(parameters: CompletionParameters): String? {
        val document = parameters.editor.document
        val path = parameters.originalFile.virtualFile?.path ?: return null
        // the place the word at the caret begins at: the same for the list that is narrowed while the word is typed
        val text = document.immutableCharSequence
        val offset = parameters.offset - GoCompletionOrder.typed(text, parameters.offset).length
        expected?.takeIf { it.stamp == document.modificationStamp && it.offset == offset && it.path == path }?.let { return it.type }
        val type = GoExpectedTypes.byText(text, offset) ?: GoExpectedTypes.enclosingCall(text, offset)?.let { parameterType(parameters, offset) }
        expected = Expected(document.modificationStamp, offset, path, type)
        return type
    }

    /** The type of the parameter the caret is at, from the signature help of the server. */
    private fun parameterType(parameters: CompletionParameters, offset: Int): String? {
        val file = parameters.originalFile.virtualFile ?: return null
        val client: LspClient = Gopls.client(parameters.originalFile.project) ?: return null
        val help = Gopls.signatureHelp(client, file, Gopls.position(parameters.editor.document, offset), SIGNATURE_TIMEOUT_MS) ?: return null
        val signature = help.signatures?.getOrNull(help.activeSignature ?: 0) ?: return null
        val labels = signature.parameters.orEmpty().map { parameter -> parameter.label?.let { if (it.isLeft) it.left else signature.label.substring(it.right.first, it.right.second) }.orEmpty() }
        val index = signature.activeParameter ?: help.activeParameter ?: 0
        // past the last parameter is a value of a variadic one
        val label = labels.getOrNull(index) ?: labels.lastOrNull()?.takeIf { "..." in it } ?: return null
        return GoExpectedTypes.parameterType(label)
    }

    private fun isCall(item: CompletionItem): Boolean = item.kind == CompletionItemKind.Function || item.kind == CompletionItemKind.Method

    private fun hasParameters(item: CompletionItem): Boolean = item.detail?.trim()?.removePrefix("func")?.trimStart()?.startsWith("()") == false

    private class Bold(element: LookupElement) : LookupElementDecorator<LookupElement>(element) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            presentation.isItemTextBold = true
        }
    }

    /** After the call is written, caret between its brackets: the parameters above it, and the list of what the first of them takes. */
    private class WithArguments(element: LookupElement) : LookupElementDecorator<LookupElement>(element) {
        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            val before = context.laterRunnable
            val editor = context.editor
            val project = context.project
            context.setLaterRunnable {
                before?.run()
                if (project.isDisposed || editor.isDisposed) return@setLaterRunnable
                // only where the caret has been left inside the brackets: a function chosen as a value has none
                val offset = editor.caretModel.offset
                if (editor.document.immutableCharSequence.getOrNull(offset - 1) != '(') return@setLaterRunnable
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            }
        }
    }

    /**
     * A struct type chosen where a value is expected becomes a literal, caret between its braces, as in GoLand: `c := http.Client{}`.
     * Where a type is expected (`var c http.Client`, a parameter, a field) nothing is added.
     */
    private class WithBraces(element: LookupElement, private val struct: Boolean) : LookupElementDecorator<LookupElement>(element) {
        override fun handleInsert(context: InsertionContext) {
            super.handleInsert(context)
            val before = context.laterRunnable
            val editor = context.editor
            val project = context.project
            val file = context.file.virtualFile
            context.setLaterRunnable {
                before?.run()
                if (project.isDisposed || editor.isDisposed) return@setLaterRunnable
                val end = editor.caretModel.offset
                val text = editor.document.immutableCharSequence
                val name = GoStructLiterals.bareName(text, end) ?: return@setLaterRunnable
                if (name.second != end || !GoStructLiterals.isValuePlace(text, name.first)) return@setLaterRunnable
                if (struct) return@setLaterRunnable braces(project, editor, end)
                val client = Gopls.client(project) ?: return@setLaterRunnable
                val stamp = editor.document.modificationStamp
                val position = Gopls.position(editor.document, end - 1)
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (file == null || !isStruct(client, file, position)) return@executeOnPooledThread
                    ApplicationManager.getApplication().invokeLater {
                        // nothing typed since: the braces go where the name ends
                        if (!project.isDisposed && !editor.isDisposed && editor.document.modificationStamp == stamp && editor.caretModel.offset == end) braces(project, editor, end)
                    }
                }
            }
        }

        /** The import of the package has just been written: the server may need a moment to know the type. */
        private fun isStruct(client: LspClient, file: VirtualFile, position: Position): Boolean {
            repeat(HOVER_ATTEMPTS) { attempt ->
                Gopls.hover(client, file, position, HOVER_TIMEOUT_MS)?.let { return STRUCT.containsMatchIn(it) }
                if (attempt < HOVER_ATTEMPTS - 1) Thread.sleep(HOVER_RETRY_MS)
            }
            return false
        }

        private fun braces(project: Project, editor: Editor, offset: Int) {
            WriteCommandAction.runWriteCommandAction(project, "Struct Literal", null, {
                editor.document.insertString(offset, "{}")
                editor.caretModel.moveToOffset(offset + 1)
            })
        }
    }

    private companion object {
        // asked while the list is made: better no bold items than a list that is late
        const val SIGNATURE_TIMEOUT_MS = 400
        const val HOVER_TIMEOUT_MS = 1_000
        const val HOVER_ATTEMPTS = 3
        const val HOVER_RETRY_MS = 300L
        val STRUCT = Regex("""\btype\s+\w+(\[[^\]]*])?\s+struct\b""")
    }
}

/** `, ` inside the brackets of a call: the list for the next argument opens by itself, as it does for the first one. */
class GoplsArgumentTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped != ' ' || file !is GoFile || !GoSettings.getInstance().completionArguments) return Result.CONTINUE
        if (GoFeatures.native(GoFeature.COMPLETION, project)) return Result.CONTINUE
        val text = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        if (text.getOrNull(offset - 1) != ',' || GoExpectedTypes.enclosingCall(text, offset) == null || Gopls.client(project) == null) return Result.CONTINUE
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }
}
