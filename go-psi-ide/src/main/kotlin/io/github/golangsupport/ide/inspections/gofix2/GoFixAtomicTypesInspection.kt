package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec

/**
 * Typed atomics (go1.19): a variable of type `int32` / `int64` / `uint32` / `uint64` without an initial value whose every use is
 * `&n` passed to the matching `sync/atomic` function (`AddInt64`, `LoadInt64`, `StoreInt64`, `SwapInt64`, `CompareAndSwapInt64`)
 * → `var n atomic.Int64` with `n.Add(d)`, `n.Load()`, …. Locals, and unexported package-level variables used only in their file
 * (no other file of the directory mentions the name). Struct fields are not handled.
 */
class GoFixAtomicTypesInspection : GoFix2InspectionBase() {
    override val minVersion = "1.19"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoVarDefinition) return null
        val spec = element.parent as? GoVarSpec ?: return null
        val type = spec.type ?: return null
        val typed = TYPES[type.text] ?: return null
        if (spec.varDefinitionList.size != 1 || spec.expressionList.isNotEmpty()) return null
        val name = element.name?.takeIf { it != "_" } ?: return null
        val declaration = spec.parent as? GoVarDeclaration ?: return null
        val scope: PsiElement = when {
            declaration.parent is GoFile -> {
                if (name.first().isUpperCase() || mentionedElsewhere(file, name)) return null
                file
            }
            GoFixPsi.isStatementList(declaration.parent) -> GoFixPsi.enclosingBody(declaration) ?: return null
            else -> return null
        }
        val uses = GoFixPsi.references(scope, element)
        if (uses.isEmpty()) return null
        val calls = uses.map { atomicCall(it, typed) ?: return null }
        return GoFixFinding(element, "Variable '$name' is accessed only through sync/atomic functions; it can be declared as atomic.$typed",
            listOf("Use atomic.$typed" to { s ->
                listOf(GoFixEdit.replace(type, "${s.prefix("sync/atomic", "atomic")}$typed")) + calls.map { (call, op) ->
                    GoFixEdit.replace(call, "$name.$op(${GoFixPsi.args(call).drop(1).joinToString(", ") { it.text }})")
                }
            }), element.nameIdentifier?.textRange?.shiftLeft(element.textRange.startOffset))
    }

    /** The `atomic.<Op><typed>(&n, …)` call around the use [ref], with `<Op>`. */
    private fun atomicCall(ref: GoReferenceExpression, typed: String): Pair<GoCallExpr, String>? {
        val address = ref.parent as? GoUnaryExpr ?: return null
        if (address.and == null) return null
        val call = (address.parent as? GoArgumentList)?.parent as? GoCallExpr ?: return null
        if (GoFixPsi.args(call).firstOrNull() !== address || GoFixPsi.hasEllipsis(call)) return null
        val names = OPS.map { it + typed }.toTypedArray()
        val key = GoFixPsi.callee(call, *names) ?: return null
        if (!key.startsWith("sync/atomic.")) return null
        return call to key.removePrefix("sync/atomic.").removeSuffix(typed)
    }

    /** Whether another Go file of the directory mentions [name] (the variable could be used there). */
    private fun mentionedElsewhere(file: GoFile, name: String): Boolean {
        val directory = file.originalFile.containingDirectory ?: return true
        val word = Regex("""\b${Regex.escape(name)}\b""")
        return directory.files.any { it is GoFile && it != file.originalFile && word.containsMatchIn(it.text) }
    }

    companion object {
        private val TYPES = mapOf("int32" to "Int32", "int64" to "Int64", "uint32" to "Uint32", "uint64" to "Uint64")
        private val OPS = listOf("Add", "Load", "Store", "Swap", "CompareAndSwap")
    }
}
