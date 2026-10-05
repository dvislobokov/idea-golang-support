package io.github.golangsupport.ide.injection.sh

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.impl.GoGenerateCommentImpl

/**
 * Injects Shell Script into the command of a `//go:generate` line, as GoLand does. Lives in its own descriptor (go-psi-ide-injection-sh.xml),
 * loaded through an optional dependency on the Shell Script plugin; the language is looked up by id, so nothing here links against that plugin.
 * Only `//go:generate` comments are hosts ([GoGenerateCommentImpl]); for a `-command NAME` alias the injected text is the aliased command.
 */
class GoGenerateShellInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(GoGenerateCommentImpl::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? GoGenerateCommentImpl ?: return
        if (!host.isValidHost) return
        val shell = Language.findLanguageByID(SHELL_SCRIPT) ?: return
        val range = GoGenerateCommand.commandRange(host.text) ?: return
        registrar.startInjecting(shell).addPlace(null, null, host, range).doneInjecting()
    }

    private companion object {
        const val SHELL_SCRIPT = "Shell Script"
    }
}

/** The pure part: where the command of a `//go:generate` comment is. */
object GoGenerateCommand {
    /** The range of the command in [comment] (`//go:generate cmd args`), past a `-command NAME` alias; null when there is no command. */
    fun commandRange(comment: String): TextRange? {
        if (!comment.startsWith(GoGenerateCommentImpl.PREFIX)) return null
        val end = comment.trimEnd().length
        var start = skipBlanks(comment, GoGenerateCommentImpl.PREFIX.length)
        if (comment.startsWith(ALIAS, start) && start + ALIAS.length < end && comment[start + ALIAS.length].isBlank()) {
            start = skipBlanks(comment, start + ALIAS.length)
            while (start < end && !comment[start].isBlank()) start++
            start = skipBlanks(comment, start)
        }
        return if (start < end) TextRange(start, end) else null
    }

    private fun Char.isBlank(): Boolean = this == ' ' || this == '\t'

    private fun skipBlanks(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isBlank()) i++
        return i
    }

    private const val ALIAS = "-command"
}
