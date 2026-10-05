package io.github.golangsupport.ide.inspections.unused

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.ide.inspections.project.GoProjectPackages
import io.github.golangsupport.ide.inspections.project.GoSafeDeleteUnusedFix
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * GoLand's "Declaration redundancy" unused-declaration inspections: a function, type, constant or package-level variable that nothing
 * refers to outside its own declaration. One id per kind and visibility, as GoLand splits them; each subclass says which declarations it
 * owns ([owns]) so that a declaration is reported by exactly one of them.
 *
 * Which exported names are checked follows GoLand's findings on the probe files (`docs/goland-analysis/dumps/highlight-internal-*.txt`):
 * exported functions, constants and variables of a package whose every importer is project code ([GoProjectPackages.isClosed]: an
 * `internal/` package or a package of an application module) are reported like unexported ones; exported types only in `package main`
 * (GoLand leaves `Holder` / `Config` of an internal package alone). Library API is never reported.
 *
 * Usages come from the platform's reference search in the declaration's use scope (the package directory for unexported names, the
 * project for exported ones), the same search as the "usages" code vision. Not uses: references inside the declaration itself (recursion,
 * a self-referencing type) and, for a type, references inside its own methods (receivers included). Skipped: methods (interfaces call them
 * implicitly), `init`, `main` of `package main`, `_`, test entry points (`Test*`, `Benchmark*`, `Example*`, `Fuzz*` in `_test.go`),
 * functions without a body (assembly) or with `//export` / `//go:linkname`, generated files. Fix: Safe Delete.
 */
abstract class GoUnusedDeclarationInspectionBase : GoAnalysisInspectionBase() {

    /** Whether this inspection reports [element] (already known to be a candidate declaration) declared in [file]. */
    protected abstract fun owns(element: GoNamedElement, file: GoFile): Boolean

    /** `function`, `type`, `constant`, `global variable`: the word of the message. */
    protected abstract fun kind(element: GoNamedElement): String

    override fun isApplicable(file: GoFile): Boolean = !GoAnalysisScope.isGenerated(file)

    final override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoNamedElement || !GoUnusedDeclarations.isCandidate(element, file) || !owns(element, file)) return
        if (GoUnusedDeclarations.isUsed(element)) return
        val identifier = element.nameIdentifier ?: return
        holder.registerProblem(identifier, "Unused ${kind(element)} '${element.name}'", ProblemHighlightType.LIKE_UNUSED_SYMBOL, GoSafeDeleteUnusedFix())
    }

    /** Exported names are checked only where every importer is project code. */
    protected fun closed(file: GoFile): Boolean {
        val vf = GoPsiUtil.originalVirtualFile(file)
        val dir = vf.parent ?: return false
        return vf.fileSystem.protocol == "file" && GoProjectPackages.isClosed(file.project, dir)
    }
}

/** `GoUnusedFunction`: an unexported function, or an exported one of an internal / application package other than `main`. */
class GoUnusedFunctionInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean =
        element is GoFunctionDeclaration && (!element.isPublic() || file.packageName != "main" && closed(file))

    override fun kind(element: GoNamedElement): String = "function"
}

/** `GoUnusedExportedFunction`: an exported function of `package main` (nothing can import it). */
class GoUnusedExportedFunctionInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean = element is GoFunctionDeclaration && element.isPublic() && file.packageName == "main"
    override fun kind(element: GoNamedElement): String = "function"
}

/** `GoUnusedType`: an unexported type, package-level or local. */
class GoUnusedTypeInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean = element is GoTypeSpec && !element.isPublic()
    override fun kind(element: GoNamedElement): String = "type"
}

/** `GoUnusedExportedType`: an exported type of `package main`. */
class GoUnusedExportedTypeInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean = element is GoTypeSpec && element.isPublic() && file.packageName == "main"
    override fun kind(element: GoNamedElement): String = "type"
}

/** `GoUnusedConst`: a constant, package-level or local; exported ones where every importer is project code. */
class GoUnusedConstInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean = element is GoConstDefinition && (!element.isPublic() || closed(file))
    override fun kind(element: GoNamedElement): String = "constant"
}

/** `GoUnusedGlobalVariable`: a package-level variable (locals are the compiler's "declared and not used"); exported ones as for constants. */
class GoUnusedGlobalVariableInspection : GoUnusedDeclarationInspectionBase() {
    override fun owns(element: GoNamedElement, file: GoFile): Boolean =
        element is GoVarDefinition && PsiTreeUtil.getParentOfType(element, GoBlock::class.java) == null && (!element.isPublic() || closed(file))

    override fun kind(element: GoNamedElement): String = "global variable"
}

internal object GoUnusedDeclarations {

    private val TEST_PREFIXES = listOf("Test", "Benchmark", "Example", "Fuzz")

    /** A function (not a method), type spec, constant or variable whose name may be reported at all. */
    fun isCandidate(element: GoNamedElement, file: GoFile): Boolean {
        if (element !is GoFunctionDeclaration && element !is GoTypeSpec && element !is GoConstDefinition && element !is GoVarDefinition) return false
        val name = element.name ?: return false
        if (name == "_") return false
        if (element is GoFunctionDeclaration) {
            if (name == "init" || name == "main" && file.packageName == "main") return false
            if (file.isTestFile && (TEST_PREFIXES.any(name::startsWith))) return false
            if (element.block == null || hasLinkDirective(element)) return false
        }
        return true
    }

    /** Whether some reference outside the declaration (and, for a type, outside its methods) resolves to [element]. */
    fun isUsed(element: GoNamedElement): Boolean {
        val project = element.project
        val scope = element.useScope.intersectWith(GlobalSearchScope.projectScope(project))
        return !ReferencesSearch.search(element, scope).forEach(Processor { ref -> !counts(element, ref.element) })
    }

    private fun counts(element: GoNamedElement, at: PsiElement): Boolean {
        if (PsiTreeUtil.isAncestor(element, at, false)) return false
        if (element is GoTypeSpec) {
            val method = PsiTreeUtil.getParentOfType(at, GoMethodDeclaration::class.java) ?: return true
            return method.receiverTypeName != element.name || method.containingFile.originalFile.containingDirectory != element.containingFile.originalFile.containingDirectory
        }
        return true
    }

    private fun hasLinkDirective(decl: GoFunctionDeclaration): Boolean {
        val func = GoPsiUtil.childToken(decl, GoTypes.FUNC) ?: return false
        val directives = decl.text.substring(0, func.startOffsetInParent)
        return "//export " in directives || "//go:linkname" in directives
    }
}
