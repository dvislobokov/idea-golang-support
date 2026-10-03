package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoPointerType as PsiPointerType
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType

/**
 * A value that contains a lock is copied (vet `copylocks`). A lock is a type whose pointer method set has `Lock` and `Unlock`
 * (`sync.Mutex`, `RWMutex`, `WaitGroup`, `Once`, `Cond`, `Map`, `Pool`, the `sync/atomic` types, ...), found through struct fields and
 * arrays, not through pointers, slices, maps or channels. Reported: parameters and receivers declared by value, call arguments,
 * returns, assignments and declarations from an existing value (not from a composite literal or a call), and the value variable
 * of a `range`. Fix for a receiver: use a pointer receiver.
 */
class GoCopyLocksInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoFunctionOrMethodDeclaration && element !is GoFunctionLit && element !is GoAssignmentStatement && element !is GoShortVarDeclaration &&
            element !is GoVarSpec && element !is GoRangeClause && element !is GoReturnStatement && element !is GoCallExpr
        ) return
        val locks = Locks(GoSemanticService.getInstance(file.project), GoLintPsi.packagePath(file))
        when (element) {
            is GoFunctionOrMethodDeclaration -> {
                val name = element.name ?: "func"
                if (element is GoMethodDeclaration) element.receiver?.let { receiver(it, name, locks, holder) }
                parameters(element.signature, name, locks, holder)
            }
            is GoFunctionLit -> parameters(element.signature, "func", locks, holder)
            is GoAssignmentStatement -> if (element.assignOp.text == "=") {
                val left = element.leftHandExprList.expressionList
                val right = element.expressionList
                if (left.size == right.size) for (i in left.indices) {
                    if (left[i].text == "_") continue
                    locks.pathOfValue(right[i])?.let { holder.registerProblem(right[i], "assignment copies lock value to ${left[i].text}: $it") }
                }
            }
            is GoShortVarDeclaration -> if (element.varDefinitionList.size == element.expressionList.size) for ((i, e) in element.expressionList.withIndex()) {
                locks.pathOfValue(e)?.let { holder.registerProblem(e, "assignment copies lock value to ${element.varDefinitionList[i].text}: $it") }
            }
            is GoVarSpec -> if (element.varDefinitionList.size == element.expressionList.size) for ((i, e) in element.expressionList.withIndex()) {
                locks.pathOfValue(e)?.let { holder.registerProblem(e, "variable declaration copies lock value to ${element.varDefinitionList[i].text}: $it") }
            }
            is GoRangeClause -> if (element.define != null && element.varDefinitionList.size == 2) {
                val value = element.varDefinitionList[1]
                if (value.name != "_") locks.path(locks.service.declarationType(value))?.let { holder.registerProblem(value, "range var ${value.name} copies lock: $it") }
            }
            is GoReturnStatement -> for (e in element.expressionList) locks.pathOfValue(e)?.let { holder.registerProblem(e, "return copies lock value: $it") }
            is GoCallExpr -> call(element, locks, holder)
        }
    }

    private fun receiver(receiver: GoReceiver, name: String, locks: Locks, holder: ProblemsHolder) {
        val type = receiver.type ?: return
        if (type is PsiPointerType) return
        val path = locks.path(locks.service.declarationType(receiver)) ?: return
        holder.registerProblem(type, "$name passes lock by value: $path", GoPointerReceiverFix())
    }

    private fun parameters(signature: GoSignature?, name: String, locks: Locks, holder: ProblemsHolder) {
        for (declaration in signature?.parameters?.parameterDeclarationList.orEmpty()) {
            val type = declaration.type ?: continue
            if (type is PsiPointerType || declaration.isVariadic) continue
            val definition = declaration.paramDefinitionList.firstOrNull() ?: continue
            val path = locks.path(locks.service.declarationType(definition)) ?: continue
            holder.registerProblem(type, "$name passes lock by value: $path")
        }
    }

    private fun call(call: GoCallExpr, locks: Locks, holder: ProblemsHolder) {
        val callee = GoLintPsi.calleeReference(call)
        if (callee != null && callee.expression == null && callee.identifier.text in BUILTINS) {
            val target = locks.service.resolve(callee).singleOrNull()
            if (target != null && GoLintPsi.isBuiltin(target)) return
        }
        if (callee != null && callee.identifier.text in UNSAFE && callee.expression != null) return
        for (argument in call.arguments) {
            if (argument !is GoExpression) continue
            locks.pathOfValue(argument)?.let { holder.registerProblem(argument, "call of ${call.expression.text} copies lock value: $it") }
        }
    }

    private class Locks(val service: GoSemanticService, private val currentPackage: String?) {

        /** The lock path of the value of [expression] when evaluating it copies an existing value, or null. */
        fun pathOfValue(expression: GoExpression): String? {
            var e: GoExpression = expression
            while (e is GoParenthesesExpr) e = e.inner as? GoExpression ?: return null
            when (e) {
                is GoCompositeLit, is GoFunctionLit, is GoCallExpr -> return null
                is GoUnaryExpr -> {
                    if (e.arrow != null || e.and != null) return null
                    // A call may return a pointer to a zero value: `*f()`.
                    if (e.mul != null && GoLintPsi.unparen(e.expression) is GoCallExpr) return null
                }
                else -> {}
            }
            return path(service.typeOf(e))
        }

        fun path(type: GoType): String? = find(type, HashSet())?.joinToString(" contains ")

        /** Outer to inner type names down to the lock. */
        private fun find(type: GoType, seen: MutableSet<GoNamedType>): List<String>? = when (type) {
            is GoNamedType -> findNamed(type, seen)
            is GoArrayType -> find(type.elem, seen)
            is GoStructType -> fields(type, seen)
            else -> null
        }

        private fun findNamed(named: GoNamedType, seen: MutableSet<GoNamedType>): List<String>? {
            if (!seen.add(named)) return null
            val methods = named.methods
            // vet's sync.Locker shape: `Lock()` and `Unlock()` without parameters or results (internal/gate's `Unlock(set bool)` is no lock)
            fun locker(name: String) = methods.any { it.name == name && it.pointerReceiver && it.signature.params.isEmpty() && it.signature.results.isEmpty() }
            if (locker("Lock") && locker("Unlock")) return listOf(label(named))
            val inner = when (val underlying = named.underlying()) {
                is GoArrayType -> find(underlying.elem, seen)
                is GoStructType -> fields(underlying, seen)
                else -> null
            }
            return inner?.let { listOf(label(named)) + it }
        }

        private fun fields(struct: GoStructType, seen: MutableSet<GoNamedType>): List<String>? {
            for (field in struct.fields) find(field.type, seen)?.let { return it }
            return null
        }

        private fun label(named: GoNamedType): String {
            val path = named.pkgPath
            return if (path == null || path == currentPackage) named.name else path.substringAfterLast('/') + "." + named.name
        }
    }

    private companion object {
        val BUILTINS = setOf("append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make", "max", "min", "new", "panic", "print", "println", "real", "recover")
        val UNSAFE = setOf("Sizeof", "Alignof", "Offsetof")
    }
}

/** `func (m T)` to `func (m *T)`. */
class GoPointerReceiverFix : LocalQuickFix {
    override fun getFamilyName(): String = "Use a pointer receiver"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val type = descriptor.psiElement ?: return
        val document = GoImportEdits.document(type.containingFile) ?: return
        document.insertString(type.textRange.startOffset, "*")
        GoImportEdits.commit(type.containingFile, document)
    }
}
