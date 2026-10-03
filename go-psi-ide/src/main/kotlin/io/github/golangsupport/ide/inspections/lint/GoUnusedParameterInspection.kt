package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParameterDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
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
        // exported functions and methods: callers in other modules fix the signature (GOROOT reported 1004 parameters without this);
        // `t *testing.T` and friends: test helpers keep the shape of their siblings
        if (decl.name?.firstOrNull()?.isUpperCase() == true) return
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
            holder.registerProblem(def, "Parameter '$name' is never used", *fixes.toTypedArray())
        }
    }
}

private val TESTING_TYPES = setOf("*testing.T", "*testing.B", "*testing.F", "testing.TB", "*testing.M")

internal object GoUnusedParameters {

    private val TEST_PREFIXES = listOf("Test", "Benchmark", "Fuzz", "Example")

    /** A call of the function: [call] whose callee is the reference; [methodExpression] for `T.M(recv, …)` (arguments shifted by one). */
    class CallSite(val call: GoCallExpr, val methodExpression: Boolean)

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
        val result = ArrayList<CallSite>()
        var value = false
        // only unexported declarations get here: their references live in the files of their own directory, which also works for library
        // packages (GOROOT: `arch.LoadRegResult = loadRegResult` was missed by a project-scope search)
        val dir = decl.containingFile.originalFile.virtualFile?.parent
        val scope = if (dir != null) com.intellij.psi.search.GlobalSearchScopesCore.directoryScope(decl.project, dir, false) else GlobalSearchScope.projectScope(decl.project)
        ReferencesSearch.search(decl, scope).forEach(Processor { reference ->
            ProgressManager.checkCanceled()
            val element = reference.element
            if (element.containingFile !is GoFile || PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) return@Processor true
            val call = (element as? GoReferenceExpression)?.let(::callOf)
            if (call == null) {
                value = true
                return@Processor false
            }
            result += CallSite(call, decl is GoMethodDeclaration && isTypeOperand(element.qualifier))
            true
        })
        return if (value) null else result
    }

    /** The call whose callee is [ref] (through parentheses and an instantiation `f[int]`). */
    private fun callOf(ref: GoReferenceExpression): GoCallExpr? {
        var callee: PsiElement = ref
        while (true) {
            val parent = callee.parent
            callee = when {
                parent is GoParenthesesExpr -> parent
                parent is GoIndexOrSliceExpr && parent.firstChild === callee -> parent
                else -> break
            }
        }
        val call = callee.parent as? GoCallExpr ?: return null
        return call.takeIf { it.expression === callee }
    }

    /** Whether [qualifier] names a type (`T.M`, `(*T).M`): a method expression. */
    private fun isTypeOperand(qualifier: PsiElement?): Boolean {
        var e = qualifier ?: return false
        while (true) {
            e = when (e) {
                is GoParenthesesExpr -> e.inner ?: return false
                is GoUnaryExpr -> e.expression ?: return false
                else -> break
            }
        }
        if (e is GoType) return true
        val ref = e as? GoReferenceExpression ?: return false
        return GoSemanticService.getInstance(ref.project).resolve(ref).any { it is GoTypeSpec }
    }

    fun definitions(decl: GoFunctionOrMethodDeclaration): List<GoParamDefinition> =
        declarations(decl).flatMap { it.paramDefinitionList }

    private fun declarations(decl: GoFunctionOrMethodDeclaration): List<GoParameterDeclaration> =
        decl.signature?.parameters?.parameterDeclarationList.orEmpty()

    /** Argument slot of [def] (a declaration without names takes one slot). */
    private fun slot(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition): Int {
        var index = 0
        for (pd in declarations(decl)) {
            val defs = pd.paramDefinitionList
            if (defs.isEmpty()) {
                index++
                continue
            }
            val i = defs.indexOf(def)
            if (i >= 0) return index + i
            index += defs.size
        }
        return -1
    }

    private fun arity(decl: GoFunctionOrMethodDeclaration): Int = declarations(decl).sumOf { maxOf(1, it.paramDefinitionList.size) }

    private fun isVariadic(decl: GoFunctionOrMethodDeclaration): Boolean = declarations(decl).lastOrNull()?.isVariadic == true

    /** The arguments of [site] that bind to [slot] (all the trailing ones for a variadic slot), or null when they cannot be told apart. */
    private fun argumentsAt(site: CallSite, slot: Int, arity: Int, variadic: Boolean): List<PsiElement>? {
        if (site.methodExpression) return null
        val args = site.call.arguments
        val spread = site.call.argumentList?.node?.findChildByType(GoTypes.ELLIPSIS) != null
        // `f(g())` with a multi-value g gives one argument for several parameters
        val fits = if (variadic && !spread) args.size >= arity - 1 else args.size == arity
        if (!fits) return null
        return if (variadic && slot == arity - 1) args.subList(slot, args.size) else listOf(args[slot])
    }

    fun removable(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition, calls: List<CallSite>): Boolean {
        val slot = slot(decl, def)
        if (slot < 0) return false
        val arity = arity(decl)
        val variadic = isVariadic(decl)
        return calls.all { site -> argumentsAt(site, slot, arity, variadic)?.all(::isPure) == true }
    }

    /** No calls (conversions included, conservatively) and no receives: dropping the argument changes nothing. */
    private fun isPure(e: PsiElement): Boolean = PsiTreeUtil.findChildOfType(e, GoCallExpr::class.java, false) == null &&
        PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java).none { it.operator === GoTypes.ARROW } && !(e is GoUnaryExpr && e.operator === GoTypes.ARROW)

    /** The deletions that remove [def] from the signature and its argument from every call, by file. */
    fun removal(decl: GoFunctionOrMethodDeclaration, def: GoParamDefinition, calls: List<CallSite>): Map<PsiFile, List<TextRange>>? {
        val slot = slot(decl, def)
        val arity = arity(decl)
        val variadic = isVariadic(decl)
        val result = LinkedHashMap<PsiFile, MutableList<TextRange>>()
        val parameters = decl.signature?.parameters ?: return null
        val pd = def.parent as? GoParameterDeclaration ?: return null
        val names = pd.paramDefinitionList
        val signatureRange = if (names.size > 1) {
            val i = names.indexOf(def)
            listRemoval(names, i, i + 1, 0, 0, null)
        } else {
            val decls = declarations(decl)
            val i = decls.indexOf(pd)
            listRemoval(decls, i, i + 1, parenOpen(parameters) ?: return null, parenClose(parameters) ?: return null, null)
        }
        result.getOrPut(decl.containingFile) { ArrayList() } += signatureRange
        for (site in calls) {
            val removed = argumentsAt(site, slot, arity, variadic) ?: return null
            if (removed.isEmpty()) continue // `f(a)` for `f(a int, xs ...int)`: nothing bound to the variadic slot
            val list = site.call.argumentList ?: return null
            val args = site.call.arguments
            val from = args.indexOf(removed.first())
            val to = from + removed.size
            val ellipsis = list.node.findChildByType(GoTypes.ELLIPSIS)?.textRange?.endOffset
            result.getOrPut(site.call.containingFile) { ArrayList() } +=
                listRemoval(args, from, to, parenOpen(list) ?: return null, parenClose(list) ?: return null, ellipsis)
        }
        return result
    }

    private fun parenOpen(e: PsiElement): Int? = e.node.findChildByType(GoTypes.LPAREN)?.textRange?.endOffset

    private fun parenClose(e: PsiElement): Int? = e.node.getChildren(null).lastOrNull { it.elementType == GoTypes.RPAREN }?.startOffset

    /** The range that removes `items[from until to]` with their separating commas; everything between the parentheses when all go. */
    private fun listRemoval(items: List<PsiElement>, from: Int, to: Int, open: Int, close: Int, tailEnd: Int?): TextRange = when {
        from == 0 && to == items.size -> TextRange(open, close)
        to < items.size -> TextRange(items[from].textRange.startOffset, items[to].textRange.startOffset)
        else -> TextRange(items[from - 1].textRange.endOffset, maxOf(tailEnd ?: 0, items.last().textRange.endOffset))
    }

    fun parameterOf(descriptor: ProblemDescriptor): GoParamDefinition? =
        descriptor.psiElement?.let { it as? GoParamDefinition ?: PsiTreeUtil.getParentOfType(it, GoParamDefinition::class.java, false) }

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
