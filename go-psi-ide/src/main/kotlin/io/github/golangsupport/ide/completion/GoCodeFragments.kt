package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.impl.source.PsiFileImpl
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile

/**
 * Go code fragments: one line of Go edited in a dialog (a type, a result list, an expression) whose names resolve in a context file.
 * The fragment is a light [GoFile] holding only that text (it parses as garbage on its own); completion moves into a copy of the
 * context file with the fragment appended as a declaration ([Kind.wrap]), whose original is the context file, so scopes, imports and
 * the package come from it ([completionParameters], [GoCompletionContext.originalFile]). Imports chosen in a fragment are not
 * inserted into it ([GoImportInserter]): the refactoring that reads the text adds them to the files it changes.
 */
object GoCodeFragments {

    /** What the fragment holds, and the declaration it becomes in the context copy: the text between [prefix] and [suffix]. */
    enum class Kind {
        /** A type (`...T` of a variadic parameter allowed): `var _ T`. */
        TYPE,
        /** Results as Change Signature writes them: `int`, `(int, error)`, `n int, err error`. */
        RESULTS,
        /** An expression (a default value for the calls): `var _ = e`. */
        EXPRESSION;

        /** The declaration text: prefix, the fragment text with offsets kept, suffix. */
        fun wrap(text: String): Triple<String, String, String> = when (this) {
            // `...T` is not a type: the dots become blanks so the offsets stay
            TYPE -> Triple("var _ ", if (text.trimStart().startsWith("...")) text.replaceFirst("...", "   ") else text, "\n")
            RESULTS -> if (text.trimStart().startsWith("(")) Triple("func _() ", text, " {}\n") else Triple("func _() (", text, ") {}\n")
            EXPRESSION -> Triple("var _ = ", text, "\n")
        }
    }

    private class Data(val context: SmartPsiElementPointer<GoFile>, val kind: Kind)

    private val DATA = Key.create<Data>("gopsi.codeFragment")

    /** A fragment of [kind] with [text], resolved in [context]; without a context it is plain Go text (colors, no completion). */
    fun create(project: Project, context: GoFile?, kind: Kind, text: String): GoFile {
        val file = PsiFileFactory.getInstance(project).createFileFromText("fragment.go", GoLanguage, text, true, false) as GoFile
        if (context != null) file.putUserData(DATA, Data(SmartPointerManager.createPointer(context), kind))
        return file
    }

    private fun data(file: PsiFile?): Data? = file?.getUserData(DATA) ?: file?.originalFile?.getUserData(DATA)

    fun isFragment(file: PsiFile?): Boolean = data(file) != null

    /** The file a fragment resolves in, or null for any other file. */
    fun contextOf(file: PsiFile?): GoFile? = data(file)?.context?.element

    fun kindOf(file: PsiFile?): Kind? = data(file)?.kind

    /**
     * [parameters] of a completion in a fragment moved into a copy of the context file with the fragment (as typed, dummy identifier
     * included) appended as a declaration; null outside a fragment or when the context is gone.
     */
    fun completionParameters(parameters: CompletionParameters): CompletionParameters? {
        val data = data(parameters.originalFile) ?: return null
        val context = data.context.element ?: return null
        val (prefix, body, suffix) = data.kind.wrap(parameters.position.containingFile.text)
        val head = context.text + "\n\n" + prefix
        val copy = PsiFileFactory.getInstance(context.project).createFileFromText(context.name, GoLanguage, head + body + suffix, false, false) as GoFile
        (copy as PsiFileImpl).setOriginalFile(context)
        val offset = head.length + parameters.offset
        val leaf = copy.findElementAt(offset) ?: return null
        return parameters.withPosition(leaf, offset)
    }
}
