package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.project.GoProjectPackages
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.refactoring.GoParameterRemoval
import io.github.golangsupport.ide.refactoring.GoParameterRemoval.CallSite
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier

/**
 * A named parameter of a function or method declaration that the body never mentions (gopls `unusedparams`). Like gopls, it stays
 * quiet where the signature is dictated from outside: no body or an empty one, a body that only panics, `init` / `main`, test,
 * benchmark, fuzz and example functions, `//export` / `//go:linkname` functions, HTTP handlers `(http.ResponseWriter, *http.Request)`,
 * methods that implement an interface method, and functions used as values (passed, assigned) anywhere in the project.
 * Fixes: rename it to `_` (keeps the signature), and remove it together with the argument at every call site — offered only when
 * every project reference is a direct call whose argument at that position has no side effects.
 */
class GoUnusedParameterInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoFunctionDeclaration && element !is GoMethodDeclaration) return
        val decl = element as GoFunctionOrMethodDeclaration
        if (!GoUnusedParameters.signatureIsFree(decl, file)) return
        // exported functions and methods: callers in other modules fix the signature (GOROOT reported 1004 parameters without this),
        // unless every importer of the package is project code (an internal or application package: GoLand reports those too);
        // `t *testing.T` and friends: test helpers keep the shape of their siblings
        if (decl.name?.firstOrNull()?.isUpperCase() == true && !GoUnusedParameters.closed(file)) return
        val unused = GoUnusedParameters.unused(decl).filter { def -> (def.parent as? GoParameterDeclaration)?.type?.text !in TESTING_TYPES }
        if (unused.isEmpty() || GoUnusedParameters.isHttpHandler(decl)) return
        if (decl is GoMethodDeclaration && GoImplementations.superMethods(decl, GlobalSearchScope.allScope(decl.project), limit = 1).isNotEmpty()) return
        // a function used as a value must keep its signature: its type is what the receiver of the value expects
        val calls = GoUnusedParameters.callSites(decl) ?: return
        for (def in unused) {
            val name = def.name ?: continue
            val fixes = ArrayList<LocalQuickFix>()
            fixes += GoRenameParameterToBlankFix()
            if (GoUnusedParameters.removable(decl, def, calls)) fixes += GoRemoveUnusedParameterFix()
            // GoLand's text and range: the whole `name Type` when the declaration has one name, else the name alone
            val declaration = def.parent as? GoParameterDeclaration
            val single = declaration?.takeIf { GoParameterRemoval.definitions(decl.signature).count { it.parent === declaration } == 1 && it.type != null }
            holder.registerProblem(single ?: def, "Unused parameter '${single?.text ?: name}'", ProblemHighlightType.LIKE_UNUSED_SYMBOL, *fixes.toTypedArray())
        }
    }
}

private val TESTING_TYPES = setOf("*testing.T", "*testing.B", "*testing.F", "testing.TB", "*testing.M")

internal object GoUnusedParameters {

    private val TEST_PREFIXES = listOf("Test", "Benchmark", "Fuzz", "Example")

    /** Whether nothing outside the body fixes the signature of [decl] (by name, directives or body shape). */
    fun signatureIsFree(decl: GoFunctionOrMethodDeclaration, file: GoFile): Boolean {
        val name = decl.name ?: return false
        if (decl is GoFunctionDeclaration && (name == "init" || name == "main")) return false
        if (file.name.endsWith("_test.go") && TEST_PREFIXES.any { name.startsWith(it) }) return false
        val func = GoPsiUtil.childToken(decl, GoTypes.FUNC) ?: return false
        val directives = decl.text.substring(0, func.startOffsetInParent)
        if ("//export " in directives || "//go:linkname" in directives) return false
        val body = decl.block ?: return false
        val statements = body.statementList
        if (statements.isEmpty()) return false
        val only = statements.singleOrNull()
        return !(only != null && only.text.startsWith("panic(") && only.text.endsWith(")"))
    }

    /** The named parameters of [decl] that no name in its body resolves to (an unresolved name of the same spelling counts as a use). */
    fun unused(decl: GoFunctionOrMethodDeclaration): List<GoParamDefinition> {
        val defs = definitions(decl).filter { it.name != null && it.name != "_" }
        if (defs.isEmpty()) return emptyList()
        val byName = defs.groupBy { it.name!! }
        val used = HashSet<PsiElement>()
        val service = GoSemanticService.getInstance(decl.project)
        PsiTreeUtil.processElements(decl.block ?: return emptyList()) { e ->
            if (e is GoReferenceExpression && e.qualifier == null) {
                val candidates = byName[e.identifier?.text]
                if (candidates != null && !used.containsAll(candidates)) {
                    val targets = service.resolve(e)
                    if (targets.isEmpty()) used += candidates else candidates.filter { it in targets }.forEach { used += it }
                }
            }
            true
        }
        return defs.filter { it !in used }
    }

    /** `func(w http.ResponseWriter, r *http.Request)`: the handler shape, required by `http.HandlerFunc`. */
    fun isHttpHandler(decl: GoFunctionOrMethodDeclaration): Boolean {
        val defs = definitions(decl)
        if (defs.size != 2) return false
        val service = GoSemanticService.getInstance(decl.project)
        return GoLintPsi.isNamedOf(service.declarationType(defs[0]), "net/http", setOf("ResponseWriter")) &&
            GoLintPsi.isNamedOf(service.declarationType(defs[1]), "net/http", setOf("Request"))
    }

    /** Every reference to [decl] in the project as a call, or null when one of them uses the function as a value. */
    fun callSites(decl: GoFunctionOrMethodDeclaration): List<CallSite>? {
        if (decl.name?.firstOrNull()?.isUpperCase() == true) return GoParameterRemoval.callSites(decl, GlobalSearchScope.projectScope(decl.project))
        // unexported declarations: their references live in the files of their own directory, which also works for library
        // packages (GOROOT: `arch.LoadRegResult = loadRegResult` was missed by a project-scope search)
        val dir = decl.containingFile.originalFile.virtualFile?.parent
        val scope = if (dir != null) GlobalSearchScopesCore.directoryScope(decl.project, dir, false) else GlobalSearchScope.projectScope(decl.project)
        return GoParameterRemoval.callSites(decl, scope)
    }

    fun definitions(decl: GoFunctionOrMethodDeclaration): List<GoParamDefinition> = GoParameterRemoval.definitions(decl.signature)

    /** Every call passes a side-effect-free argument at the slot of [def]; method expressions are left to Safe Delete (kept conservative). */
    fun removable(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition, calls: List<CallSite>): Boolean {
        val signature = decl.signature
        val slot = GoParameterRemoval.slot(signature, def)
        if (slot < 0 || calls.any { it.methodExpression }) return false
        val arity = GoParameterRemoval.arity(signature)
        val variadic = GoParameterRemoval.isVariadic(signature)
        return calls.all { site -> GoParameterRemoval.argumentsAt(site, slot, arity, variadic)?.all(GoParameterRemoval::isPure) == true }
    }

    fun removal(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition, calls: List<CallSite>): Map<PsiFile, List<TextRange>>? =
        GoParameterRemoval.removal(decl, def, calls)

    fun parameterOf(descriptor: ProblemDescriptor): GoParamDefinition? = descriptor.psiElement?.let {
        it as? GoParamDefinition ?: (it as? GoParameterDeclaration)?.let { d -> PsiTreeUtil.getChildOfType(d, GoParamDefinition::class.java) }
            ?: PsiTreeUtil.getParentOfType(it, GoParamDefinition::class.java, false)
    }

    /** Whether every importer of the package of [file] is project code (an `internal/` or application package on disk). */
    fun closed(file: GoFile): Boolean {
        val vf = GoPsiUtil.originalVirtualFile(file)
        val dir = vf.parent ?: return false
        return vf.fileSystem.protocol == "file" && GoProjectPackages.isClosed(file.project, dir)
    }

    fun declarationOf(def: GoParamDefinition): GoFunctionOrMethodDeclaration? =
        PsiTreeUtil.getParentOfType(def, GoFunctionDeclaration::class.java, GoMethodDeclaration::class.java) as? GoFunctionOrMethodDeclaration
}

/** `x int` → `_ int`: the signature stays, callers are untouched. */
class GoRenameParameterToBlankFix : LocalQuickFix {
    override fun getFamilyName(): String = "Rename to _"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val def = GoUnusedParameters.parameterOf(descriptor) ?: return
        val file = def.containingFile
        val document = GoImportEdits.document(file) ?: return
        document.replaceString(def.textRange.startOffset, def.textRange.endOffset, "_")
        GoImportEdits.commit(file, document)
    }
}

/**
 * Removes the parameter from the signature and its argument from every call in the project. Imports that only the removed text
 * used are dropped too. Calls outside the project (another module importing an exported function) are not rewritten.
 */
class GoRemoveUnusedParameterFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused parameter"

    // the fix edits other files: the default preview would run it on a copy of this file only
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Removes the parameter from the signature and the matching argument from every call in the project.")

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val def = GoUnusedParameters.parameterOf(descriptor) ?: return
        val decl = GoUnusedParameters.declarationOf(def) ?: return
        val calls = GoUnusedParameters.callSites(decl) ?: return
        if (!GoUnusedParameters.removable(decl, def, calls)) return
        val edits = GoUnusedParameters.removal(decl, def, calls) ?: return
        for ((file, ranges) in edits) {
            val go = file as? GoFile ?: continue
            val document = GoImportEdits.document(go) ?: continue
            val unusedBefore = unusedImports(go)
            for (r in ranges.distinct().sortedByDescending { it.startOffset }) document.deleteString(r.startOffset, r.endOffset)
            GoImportEdits.commit(go, document)
            val newlyUnused = GoImportEdits.unusedImportRanges(go).let { ranges -> go.imports.filter { it.textRange in ranges && it.text !in unusedBefore } }
            if (newlyUnused.isNotEmpty()) {
                GoImportEdits.removeSpecs(document, newlyUnused)
                GoImportEdits.commit(go, document)
            }
        }
    }

    private fun unusedImports(file: GoFile): Set<String> = GoImportEdits.unusedImportRanges(file).let { ranges -> file.imports.filter { it.textRange in ranges }.map { it.text }.toSet() }
}
