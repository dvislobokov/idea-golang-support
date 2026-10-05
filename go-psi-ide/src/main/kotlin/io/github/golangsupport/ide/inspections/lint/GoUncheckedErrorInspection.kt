package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.options.OptPane
import com.intellij.openapi.util.text.StringUtil
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ProjectInspectionProfileManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.ide.intentions.GoZeroValues
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * errcheck while typing: a call standing alone as a statement whose last result is `error` (`os.Open("x")`, `f.Close()`, a method
 * through an interface). errcheck's defaults: `go` / `defer` calls, `_ = f()` and type assertions are not reported, nor the symbols of
 * its embedded exclude list ([EXCLUDED]). A `//nolint` / `//nolint:errcheck` at the end of the line silences it as it silences
 * golangci-lint. The user's own excludes ([excludedFunctions], filled by "Do not report this method/function anymore") are names in the
 * same form as [EXCLUDED]; the errcheck rule of the rule engine reads them from the profile's instance ([configuredExclusions]).
 *
 * Not behind [io.github.golangsupport.ide.GoIdeFeature.DIAGNOSTICS] (so not a [io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase]):
 * gopls has no errcheck analyzer, so with diagnostics from gopls nothing else reports this before the file is saved for golangci-lint;
 * there is no second source to stand down for. Not dumb-aware: callee signatures need the stub indices.
 */
class GoUncheckedErrorInspection : LocalInspectionTool() {

    /** Functions and methods not to report (`path.Name`, `path.Type.Name`), besides errcheck's [EXCLUDED]; shown in the options. */
    @JvmField var excludedFunctions: MutableList<String> = ArrayList()

    override fun getOptionsPane(): OptPane = OptPane.pane(OptPane.stringList("excludedFunctions", "Do not report calls of (path.Func, path.Type.Method):"))

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (holder.file !is GoFile) return PsiElementVisitor.EMPTY_VISITOR
        // the errcheck rule of the rule engine reports the same calls: one underline, not two
        if (io.github.golangsupport.ide.rules.GoRuleSet.getInstance(holder.project).runs(holder.file, io.github.golangsupport.ide.rules.builtin.GoErrcheckRule.ID)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is GoCallExpr || !isUnchecked(element, excludedFunctions)) return
                val shown = GoLintPsi.calleeReference(element)?.text
                val message = if (shown != null) "Error return value of `$shown` is not checked" else "Error return value is not checked"
                holder.registerProblem(element, message, *fixes(element))
            }
        }
    }

    companion object {
        const val SHORT_NAME = "GoUncheckedError"

        /**
         * errcheck's default excludes (github.com/kisielk/errcheck, `errcheck/excludes.go`, MIT, Copyright (c) 2013 Kamil Kisiel), as names: a package
         * function `path.Name`, a method `path.Type.Name` by the static type of the receiver (pointer or not), and `fmt.Fprint*` by
         * their writer ([EXCLUDED_WRITERS]).
         */
        val EXCLUDED: Set<String> = setOf(
            "bytes.Buffer.Write", "bytes.Buffer.WriteByte", "bytes.Buffer.WriteRune", "bytes.Buffer.WriteString",
            "fmt.Print", "fmt.Printf", "fmt.Println",
            "io.PipeReader.CloseWithError", "io.PipeWriter.CloseWithError",
            "math/rand.Read", "math/rand.Rand.Read",
            "strings.Builder.Write", "strings.Builder.WriteByte", "strings.Builder.WriteRune", "strings.Builder.WriteString",
            "hash.Hash.Write",
        )
        private val FPRINT = setOf("Fprint", "Fprintf", "Fprintln")
        /** The writers `fmt.Fprint*` may ignore errors of: `*bytes.Buffer`, `*strings.Builder` (by type) and `os.Stderr` (the variable). */
        private val EXCLUDED_WRITERS = setOf("bytes.Buffer", "strings.Builder")

        const val DO_NOT_REPORT = "Do not report this method/function anymore"

        /**
         * Whether [call] stands alone as a statement, returns `error` last, is not excluded (errcheck's defaults and the user's [excluded],
         * by default those of the inspection in the current profile) and is not marked `//nolint` for errcheck.
         */
        fun isUnchecked(call: GoCallExpr, excluded: Collection<String> = configuredExclusions(call)): Boolean {
            if (!GoLintPsi.isExpressionStatement(call)) return false
            val results = GoSemanticService.getInstance(call.project).calleeSignature(call)?.results ?: return false
            if (results.isEmpty() || !GoZeroValues.isError(results.last().type)) return false
            if (isExcluded(call)) return false
            if (excluded.isNotEmpty() && calleeName(call) in excluded) return false
            return !hasNolint(call)
        }

        /** The inspection of the current profile (where the user's excludes live); null outside a project profile. */
        fun profileInstance(element: PsiElement): GoUncheckedErrorInspection? =
            InspectionProjectProfileManager.getInstance(element.project).currentProfile.getUnwrappedTool(SHORT_NAME, element) as? GoUncheckedErrorInspection

        /** The user's excludes of the current profile. */
        fun configuredExclusions(element: PsiElement): List<String> = profileInstance(element)?.excludedFunctions.orEmpty()

        /** The fixes of a finding at [call]; "Do not report this method/function anymore" adds its callee to the profile's excludes. */
        fun fixes(call: GoCallExpr): Array<LocalQuickFix> {
            val name = calleeName(call) ?: return arrayOf(GoHandleUncheckedErrorFix(), GoAssignToBlankFix())
            return arrayOf(GoHandleUncheckedErrorFix(), GoAssignToBlankFix(), GoDoNotReportCalleeFix(name))
        }

        /** The name of [call]'s callee as the excludes spell it: `path.Name` for a function, `path.Type.Name` for a method (by the receiver's static type). */
        fun calleeName(call: GoCallExpr): String? {
            val callee = GoLintPsi.calleeReference(call) ?: return null
            val name = callee.identifier.text
            val service = GoSemanticService.getInstance(call.project)
            val target = service.resolve(callee).singleOrNull()
            if (target is GoFunctionDeclaration) return GoLintPsi.packagePath(target)?.let { "$it.$name" }
            val receiver = callee.expression ?: return null
            val type = service.typeOf(receiver).let { if (it is GoPointerType) it.elem else it } as? GoNamedType ?: return null
            return "${type.pkgPath}.${type.name}.$name"
        }

        private fun isExcluded(call: GoCallExpr): Boolean {
            val callee = GoLintPsi.calleeReference(call) ?: return false
            val name = callee.identifier.text
            val target = GoSemanticService.getInstance(call.project).resolve(callee).singleOrNull()
            if (target is GoFunctionDeclaration && GoLintPsi.packagePath(target) == "fmt" && name in FPRINT) {
                return excludedWriter(call.arguments.firstOrNull() as? GoExpression)
            }
            return calleeName(call) in EXCLUDED
        }

        private fun excludedWriter(writer: GoExpression?): Boolean {
            writer ?: return false
            val service = GoSemanticService.getInstance(writer.project)
            val type = service.typeOf(writer).let { if (it is GoPointerType) it.elem else it }
            if (type is GoNamedType && "${type.pkgPath}.${type.name}" in EXCLUDED_WRITERS) return true
            val reference = GoLintPsi.unparen(writer) as? GoReferenceExpression ?: return false
            val variable = service.resolve(reference).singleOrNull() as? GoVarDefinition ?: return false
            return variable.name == "Stderr" && GoLintPsi.packagePath(variable) == "os"
        }

        private val NOLINT = Regex("""//nolint(?::([\w,-]+))?(?:\s|$)""")

        /** `//nolint` or `//nolint:…errcheck…` after the call on its line, the golangci-lint way. */
        private fun hasNolint(call: GoCallExpr): Boolean {
            val text = call.containingFile.viewProvider.contents
            val end = call.textRange.endOffset
            val rest = text.subSequence(end, GoEditText.lineEnd(text, end)).toString()
            val match = NOLINT.find(rest) ?: return false
            val linters = match.groupValues[1]
            return linters.isEmpty() || linters.split(',').any { it == "errcheck" || it == "all" }
        }
    }
}

/**
 * Handle error: `if err := f(); err != nil { return … }` for a call returning only the error, otherwise `_, err := f()` and the check
 * below it. The `return` comes from [GoIntentionText.returnStatement], like the Handle error intention and the `if err` surrounder:
 * the zero values of the enclosing function's results, the error last (a bare `return` in a function without results). The other
 * results are `_`; `err` becomes `err1`, `err2`… when the block already declares it (`:=` would declare nothing new).
 */
class GoHandleUncheckedErrorFix : LocalQuickFix {
    override fun getFamilyName(): String = "Handle error"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement as? GoCallExpr ?: return
        val statement = GoEditText.expressionStatement(call) ?: return
        val file = call.containingFile as? GoFile ?: return
        val results = GoSemanticService.getInstance(project).calleeSignature(call)?.results ?: return
        if (results.isEmpty()) return
        val text = file.viewProvider.contents
        val indent = GoEditText.indentOf(text, statement.textRange.startOffset)
        val source = GoSourceText(file)
        val signature = GoIntentionText.enclosingSignature(statement)
        val replacement = if (results.size == 1) {
            "if err := ${call.text}; err != nil {\n$indent\t${GoIntentionText.returnStatement(signature, "err", source)}\n$indent}"
        } else {
            val error = freeErrorName(statement)
            val targets = (List(results.size - 1) { "_" } + error).joinToString(", ")
            "$targets := ${call.text}\n${indent}if $error != nil {\n$indent\t${GoIntentionText.returnStatement(signature, error, source)}\n$indent}"
        }
        GoEditText.apply(file, listOf(GoEditPlan.Edit(statement.textRange.startOffset, statement.textRange.endOffset, replacement)))
        if (source.imports.isEmpty()) return
        val document = GoEditText.document(file) ?: return
        for (path in source.imports) GoImportInserter.addImport(file, document, path)
        GoEditText.apply(file, emptyList())
    }

    /** `err`, or `err1`, `err2`… the first name the statement list around [statement] does not declare already. */
    private fun freeErrorName(statement: PsiElement): String =
        generateSequence(0) { it + 1 }.map { if (it == 0) "err" else "err$it" }.first { !declaredInSameScope(statement, it) }

    /** Whether [name] visible at [statement] is declared in the scope of [statement]'s statement list (parameters count for a function body). */
    private fun declaredInSameScope(statement: PsiElement, name: String): Boolean {
        val list = statement.parent
        return GoScopes.resolveName(statement, name).any { target ->
            val declaration = target.element
            if (declaration is GoParamDefinition) {
                val owner = GoPsiUtil.functionOwner(declaration)
                (owner is GoFunctionOrMethodDeclaration || owner is GoFunctionLit) && list is GoBlock && list.parent === owner
            } else {
                PsiTreeUtil.getParentOfType(declaration, *SCOPES) === list
            }
        }
    }

    private companion object {
        /** What opens a scope between a local declaration and the statement list it may share with the call. */
        val SCOPES: Array<Class<out PsiElement>> = arrayOf(
            GoBlock::class.java, GoIfStatement::class.java, GoForStatement::class.java, GoExprSwitchStatement::class.java, GoTypeSwitchStatement::class.java,
            GoSelectStatement::class.java, GoExprCaseClause::class.java, GoTypeCaseClause::class.java, GoCommClause::class.java, GoFunctionLit::class.java,
        )
    }
}

/**
 * Do not report this method/function anymore: adds [name] to [GoUncheckedErrorInspection.excludedFunctions] of the current profile's
 * instance (also read by the errcheck rule) and restarts the highlighting. The tool is changed in place and the profile told so: the
 * ModCommand way (`AddToInspectionOptionListFix`) finds no tool through the profile's option controller in the light test profile.
 */
class GoDoNotReportCalleeFix(private val name: String) : LocalQuickFix, LowPriorityAction {
    override fun getFamilyName(): String = GoUncheckedErrorInspection.DO_NOT_REPORT

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Adds <code>${StringUtil.escapeXmlEntities(name)}</code> to the functions the inspection does not report.")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val inspection = GoUncheckedErrorInspection.profileInstance(element) ?: return
        if (name !in inspection.excludedFunctions) inspection.excludedFunctions.add(name)
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        profile.profileChanged()
        ProjectInspectionProfileManager.getInstance(project).fireProfileChanged(profile)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
}

/** `_ = f()` / `_, _ = f()`: the error is dropped on purpose (errcheck does not report blank assignments by default). */
class GoAssignToBlankFix : LocalQuickFix {
    override fun getFamilyName(): String = "Assign to blank identifier"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val call = descriptor.psiElement as? GoCallExpr ?: return
        val file = call.containingFile ?: return
        val count = GoSemanticService.getInstance(project).calleeSignature(call)?.results?.size ?: return
        val blanks = List(count.coerceAtLeast(1)) { "_" }.joinToString(", ")
        GoEditText.apply(file, listOf(GoEditPlan.Edit(call.textRange.startOffset, call.textRange.startOffset, "$blanks = ")))
    }
}
