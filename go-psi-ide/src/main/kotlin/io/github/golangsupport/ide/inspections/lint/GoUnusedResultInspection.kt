package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments

/**
 * A call of a function whose only job is its result, standing alone as a statement (vet `unusedresult`): `fmt.Sprintf`,
 * `errors.New`, the pure `strings` / `bytes` / `strconv` functions, `sort.Reverse`, `context.With*`, `time.Since` and the arithmetic
 * methods of `time.Time`. The callee is resolved, so a user function called `Replace` is not one. Fix: assign the result back to the
 * first argument (`s = strings.TrimSpace(s)`) where the result has its type. An unused `append` is the compiler's error, with the
 * same fix ([GoAssignResultFix.forUnusedAppend]).
 */
class GoUnusedResultInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoCallExpr || !GoLintPsi.isExpressionStatement(element)) return
        val callee = GoLintPsi.calleeReference(element) ?: return
        val name = callee.identifier.text
        if (name !in ALL_NAMES) return
        val target = GoSemanticService.getInstance(element.project).resolve(callee).singleOrNull() ?: return
        val shown: String
        val context: Boolean
        when (target) {
            is GoFunctionDeclaration -> {
                // an unused `append` is a compile error already; its fix lives on that error (GoCheckerInspection, seen live: the platform
                // shows only the fixes of the error when both lie on the same range)
                if (GoLintPsi.isBuiltin(target)) {
                    return
                }
                val path = GoLintPsi.packagePath(target) ?: return
                if (name !in (FUNCTIONS[path] ?: return)) return
                shown = path.substringAfterLast('/') + "." + name
                context = path == "context"
            }
            is GoMethodDeclaration -> {
                if (target.receiverTypeName != "Time" || name !in TIME_METHODS || GoLintPsi.packagePath(target) != "time") return
                shown = "time.Time.$name"
                context = false
            }
            else -> return
        }
        val message = when {
            context && name == "WithValue" -> "result of $shown is not used; the returned context is lost"
            context -> "result of $shown is not used; the returned context and cancel are lost"
            else -> "result of $shown is not used"
        }
        val fixes = if (target is GoFunctionDeclaration && FUNCTIONS[GoLintPsi.packagePath(target)]?.let { name in it } == true && name in SAME_TYPE) assignFix(element, callee) else emptyArray()
        holder.registerProblem(element, message, *fixes)
    }

    /** `x = ` in front of the call when the first argument is a plain name of the result's type. */
    private fun assignFix(call: GoCallExpr, callee: GoReferenceExpression): Array<LocalQuickFix> {
        val first = call.arguments.firstOrNull() as? GoReferenceExpression ?: return emptyArray()
        if (GoLintPsi.unparen(first) !== first) return emptyArray()
        val service = GoSemanticService.getInstance(call.project)
        if (callee.identifier.text != "append" && service.render(service.typeOf(call)) != service.render(service.typeOf(first))) return emptyArray()
        return arrayOf(GoAssignResultFix(first.text))
    }

    private companion object {
        val PURE_TEXT = setOf(
            "Replace", "ReplaceAll", "ToUpper", "ToLower", "ToTitle", "Title", "Trim", "TrimSpace", "TrimLeft", "TrimRight", "TrimPrefix", "TrimSuffix",
            "TrimFunc", "TrimLeftFunc", "TrimRightFunc", "Split", "SplitN", "SplitAfter", "SplitAfterN", "Fields", "FieldsFunc", "Join", "Repeat",
            "Map", "ToValidUTF8", "Contains", "ContainsAny", "ContainsRune", "HasPrefix", "HasSuffix", "Index", "IndexByte", "IndexAny", "IndexRune",
            "LastIndex", "LastIndexByte", "LastIndexAny", "Count", "EqualFold", "Compare",
        )
        val FUNCTIONS: Map<String, Set<String>> = mapOf(
            "fmt" to setOf("Sprint", "Sprintf", "Sprintln", "Errorf"),
            "errors" to setOf("New"),
            "strings" to PURE_TEXT,
            "bytes" to PURE_TEXT,
            "sort" to setOf("Reverse"),
            "context" to setOf("WithCancel", "WithDeadline", "WithTimeout", "WithValue", "WithCancelCause", "WithDeadlineCause", "WithTimeoutCause"),
            "strconv" to setOf("Itoa", "FormatInt", "FormatUint", "FormatBool", "FormatFloat", "Quote", "QuoteRune"),
            "time" to setOf("Since", "Until"),
        )
        val TIME_METHODS = setOf("Add", "Sub", "AddDate", "Truncate", "Round", "UTC", "Local", "In")

        /** Functions whose result has the type of the first argument. */
        val SAME_TYPE = setOf(
            "Replace", "ReplaceAll", "ToUpper", "ToLower", "ToTitle", "Title", "Trim", "TrimSpace", "TrimLeft", "TrimRight", "TrimPrefix", "TrimSuffix",
            "TrimFunc", "TrimLeftFunc", "TrimRightFunc", "Repeat", "Map", "ToValidUTF8",
        )
        val ALL_NAMES: Set<String> = FUNCTIONS.values.flatten().toSet() + TIME_METHODS
    }
}

/** Inserts `x = ` before the call. */
class GoAssignResultFix(private val target: String) : LocalQuickFix {
    companion object {
        /** The fix for the compiler's `append(xs, …) (value of type []T) is not used` at [element]: `xs = append(xs, …)`. */
        fun forUnusedAppend(element: PsiElement): GoAssignResultFix? {
            // the diagnostic element is the call or the statement around it
            val call = callAt(element) ?: return null
            if (!GoLintPsi.isExpressionStatement(call) || GoLintPsi.calleeReference(call)?.text != "append") return null
            val first = call.arguments.firstOrNull() as? io.github.golangsupport.lang.psi.GoReferenceExpression ?: return null
            return GoAssignResultFix(first.text)
        }

        private fun callAt(element: PsiElement): GoCallExpr? =
            com.intellij.psi.util.PsiTreeUtil.getNonStrictParentOfType(element, GoCallExpr::class.java)
                ?: com.intellij.psi.util.PsiTreeUtil.findChildOfType(element, GoCallExpr::class.java, false)
    }

    override fun getFamilyName(): String = "Assign the result"

    override fun getName(): String = "Assign the result to $target"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement?.let { callAt(it) } ?: return
        val document = GoImportEdits.document(call.containingFile) ?: return
        document.insertString(call.textRange.startOffset, "$target = ")
        GoImportEdits.commit(call.containingFile, document)
    }
}
