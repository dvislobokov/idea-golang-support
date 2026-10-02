package io.github.golangsupport.ide.intentions

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType

/**
 * Fill select: the cases the scope of a `select` suggests, after the existing ones and before `default`, in this order:
 *
 * - `case <-ctx.Done():` with `return …, ctx.Err()` (or the zero values / a bare `return`) for every `context.Context` in scope;
 * - `case v := <-ch:` for every channel that can receive (`v` unless a local has that name, then `v2`, …), `case ch <- <zero>:` for
 *   send-only ones;
 * - `case <-t.C:` for every `*time.Timer` and `*time.Ticker`;
 * - `case <-time.After(d):` for every `time.Duration` (never with a made-up duration; `time` is imported when missing).
 *
 * A case whose channel expression is already in the select is not added twice. [withDefault]: "Fill select with default" also
 * adds `default:` (last) when the select has none.
 */
abstract class GoFillSelectIntentionBase(private val withDefault: Boolean) : GoCodeActionIntention() {

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val select = PsiTreeUtil.getParentOfType(leaf, GoSelectStatement::class.java, false) ?: return null
        if (GoPsiUtil.functionOwner(select) != GoPsiUtil.functionOwner(leaf)) return null
        val rbrace = select.rbrace ?: return null
        val service = GoSemanticService.getInstance(file.project)
        val source = GoSourceText(file)
        val present = select.commClauseList.mapNotNull { it.commCase?.takeIf { case -> case.default == null }?.text?.let(::channelKey) }.toHashSet()
        val default = select.commClauseList.firstOrNull { it.commCase?.default != null }
        val locals = GoScopeValues.locals(select).sortedBy { it.textRange.startOffset }.map { it to service.declarationType(it) }
        val taken = GoScopeValues.localNames(select)
        val signature = GoIntentionText.enclosingSignature(select)
        val cases = ArrayList<List<String>>()
        fun add(channel: String, lines: List<String>): Boolean = present.add(channel.filterNot(Char::isWhitespace)).also { if (it) cases += lines }
        for ((e, type) in locals) if (isNamed(type, "context", "Context")) {
            add("${e.name}.Done()", listOf("case <-${e.name}.Done():", "\t" + GoIntentionText.returnStatement(signature, "${e.name}.Err()", source)))
        }
        for ((e, type) in locals) {
            val chan = type.underlying() as? GoChanType ?: continue
            if (chan.dir == GoChanDir.SEND) add(name(e), listOf("case ${e.name} <- ${source.zero(chan.elem)}:"))
            else add(name(e), listOf("case ${receiver(taken)} := <-${e.name}:"))
        }
        for ((e, type) in locals) {
            val elem = (type as? GoPointerType)?.elem ?: continue
            if (isNamed(elem, "time", "Timer") || isNamed(elem, "time", "Ticker")) add("${e.name}.C", listOf("case <-${e.name}.C:"))
        }
        for ((e, type) in locals) {
            if (!isNamed(type, "time", "Duration")) continue
            // how the file names `time`, without asking for the import yet: only a case that is added needs it
            val channel = "${GoSourceText(file).prefix("time", "time")}After(${e.name})"
            if (add(channel, listOf("case <-$channel:"))) source.prefix("time", "time")
        }
        val addDefault = withDefault && default == null
        if (cases.isEmpty() && !addDefault) return null
        val lines = cases.flatten() + if (addDefault) listOf("default:") else emptyList()
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, select.select.textRange.startOffset)
        val anchor = (default ?: rbrace).textRange.startOffset
        return GoEditPlan(listOf(GoIntentionText.insertBefore(text, anchor, indent, lines)), source.imports)
    }

    private fun name(e: GoNamedElement): String = e.name.orEmpty()

    /** `v`, or `v2`, `v3`… when a local is called `v` already. */
    private fun receiver(taken: Set<String>): String = generateSequence(1) { it + 1 }.map { if (it == 1) "v" else "v$it" }.first { it !in taken }

    private fun isNamed(type: GoType, path: String, name: String): Boolean = type is GoNamedType && type.name == name && type.pkgPath == path

    companion object {
        /**
         * The channel of a comm case's text (`case` included, without the colon): the operand of `<-` for a receive
         * (`<-ch`, `v := <-ch`, `v, ok = <-ch`), the left side for a send (`ch <- v`); spaces removed.
         */
        fun channelKey(caseText: String): String? {
            val s = caseText.trim().removePrefix("case").filterNot(Char::isWhitespace)
            val arrow = s.indexOf("<-")
            if (arrow < 0) return null
            val before = s.substring(0, arrow)
            return if (before.isEmpty() || before.endsWith("=")) s.substring(arrow + 2) else before
        }
    }
}

/** Fill select: the cases only. */
class GoFillSelectIntention : GoFillSelectIntentionBase(withDefault = false) {
    override val defaultText: String = "Fill select"
}

/** Fill select with default: the cases and a `default:` clause. */
class GoFillSelectWithDefaultIntention : GoFillSelectIntentionBase(withDefault = true) {
    override val defaultText: String = "Fill select with default"
}
