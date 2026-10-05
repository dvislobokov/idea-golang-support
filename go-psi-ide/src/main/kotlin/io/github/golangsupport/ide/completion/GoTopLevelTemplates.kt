package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import org.jetbrains.annotations.TestOnly

/**
 * Declarations GoLand offers between top-level declarations next to the `func` keyword:
 * - **`func (*T)`** (type text `Method`): `func (r *T) <caret>() {}` for the type declared last above the caret (the first type of the
 *   file below it), with the receiver name and kind its methods already use, `*T` and the lower-case first letter otherwise;
 * - **`func`** with the type text **`Implement Interface...`**: the host's Implement Interface ([IMPLEMENT_ACTION]) for that type: the
 *   caret goes to the name of the type, where the action looks for it. Offered only when the host registers the action.
 */
object GoTopLevelTemplates {
    const val METHOD = "func (*T)"
    const val IMPLEMENT = "func Implement Interface..."

    /** The id of Implement Interface in the host plugin (go-psi does not know its classes). */
    const val IMPLEMENT_ACTION = "Go.Generate.Implement"

    /** Runs the action instead of the [ActionManager] (tests): gets the action id. */
    @TestOnly @Volatile var actionRunnerForTests: ((String) -> Unit)? = null

    fun collect(context: GoCompletionContext, out: MutableList<GoCandidate>) {
        if (context.packageKeywordAllowed) return
        val type = typeFor(context.file, context.offset) ?: return
        val typeName = type.name ?: return
        val (receiver, pointer) = receiverOf(context.file, typeName)
        val text = "func ($receiver ${if (pointer) "*" else ""}$typeName) () {\n}"
        out += GoCandidate(
            METHOD, GoCandidateKind.SNIPPET, GoScopeLevel.KEYWORD, lookupString = METHOD, typeText = "Method",
            insertHandler = methodHandler(text, text.indexOf(") (") + 2),
        )
        if (actionRunnerForTests == null && ActionManager.getInstance().getAction(IMPLEMENT_ACTION) == null) return
        out += GoCandidate(
            IMPLEMENT, GoCandidateKind.SNIPPET, GoScopeLevel.KEYWORD, lookupString = IMPLEMENT, presentableText = "func",
            typeText = "Implement Interface...", icon = AllIcons.Actions.IntentionBulb, insertHandler = implementHandler(typeName),
        )
    }

    /** The type spec declared last before [offset], or the first one of the file; null when the file declares no type. */
    fun typeFor(file: GoFile, offset: Int): GoTypeSpec? {
        val types = file.types.filter { t -> t.name.let { it != null && !it.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) && it != "_" } }
        return types.lastOrNull { it.textRange.endOffset <= offset } ?: types.firstOrNull()
    }

    /** The receiver name and pointer-ness the methods of [typeName] in [file] use; the first letter in lower case and `*T` without methods. */
    fun receiverOf(file: GoFile, typeName: String): Pair<String, Boolean> {
        val methods = file.methods.filter { it.receiverTypeName == typeName }
        val name = methods.firstNotNullOfOrNull { m -> m.receiver?.name?.takeIf { it != "_" && !it.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) } }
            ?: typeName.first().lowercase()
        return name to (methods.isEmpty() || methods.any { it.isPointerReceiver })
    }

    private fun methodHandler(text: String, caret: Int) = InsertHandler<LookupElement> { ctx, _ ->
        ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, text)
        ctx.editor.caretModel.moveToOffset(ctx.startOffset + caret)
    }

    private fun implementHandler(typeName: String) = InsertHandler<LookupElement> { ctx, _ ->
        val document = ctx.document
        document.deleteString(ctx.startOffset, ctx.tailOffset)
        PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
        val file = ctx.file as? GoFile ?: return@InsertHandler
        val type = file.types.firstOrNull { it.name == typeName } ?: return@InsertHandler
        ctx.editor.caretModel.moveToOffset((type.nameIdentifier ?: return@InsertHandler).textRange.startOffset)
        actionRunnerForTests?.let { return@InsertHandler it(IMPLEMENT_ACTION) }
        val editor = ctx.editor
        // the action shows its chooser: not from inside the write action of the insertion
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val action = ActionManager.getInstance().getAction(IMPLEMENT_ACTION) ?: return@invokeLater
            ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
        }
    }
}
