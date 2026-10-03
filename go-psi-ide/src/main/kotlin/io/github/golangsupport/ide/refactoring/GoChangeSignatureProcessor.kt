package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.ui.UsageViewDescriptorAdapter
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments

/**
 * Change Signature of a function, method or interface method spec: the declaration's name, parameters and results are regenerated
 * (receiver, type parameters and doc comment kept; parameters as `a int, b int`), every reference gets the new name, and every call
 * its arguments mapped from the old slots to the new ones (defaults for new parameters; `T.M(recv, …)` keeps the receiver first; a
 * spread `f(xs...)` keeps its `...` on the variadic parameter, which stays last). Results are not followed into call sites nor
 * `return` statements: a changed result count is reported when calls use the results.
 *
 * Interface methods and the methods that implement them are changed alone (implementations, specs and calls through the interface
 * are left as they are): reported as a conflict.
 */
class GoChangeSignatureProcessor(project: Project, private val target: PsiElement, private val options: GoChangeSignatureOptions) : BaseRefactoringProcessor(project) {

    private val signature = GoChangeSignature.signatureOf(target)
    private val oldParameters = GoChangeSignature.parametersOf(signature)
    private val oldResults = GoChangeSignature.resultsOf(signature)
    private val oldName = GoChangeSignature.nameOf(target)
    private val arity = GoParameterRemoval.arity(signature)
    private val variadic = GoParameterRemoval.isVariadic(signature)

    private val nameChanges get() = options.name != oldName
    private val argumentsChange get() = GoChangeSignature.argumentsChange(oldParameters, options)
    private val parametersChange get() = options.parameters != oldParameters
    private val resultsChange get() = options.results != oldResults
    private val typeChanges get() = parametersChange || resultsChange

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptorAdapter() {
        override fun getElements(): Array<PsiElement> = arrayOf(target)

        override fun getProcessedElementsHeader(): String = "Change signature of ${GoChangeSignature.displayName(target)}"
    }

    override fun getCommandName(): String = "Change Signature of ${GoChangeSignature.displayName(target)}"

    override fun findUsages(): Array<UsageInfo> =
        ReferencesSearch.search(target, target.useScope).findAll().filter { GoParameterRemoval.isCodeReference(it.element) }.map { UsageInfo(it) }.toTypedArray()

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        GoChangeSignature.validate(oldParameters, options)?.let {
            CommonRefactoringUtil.showErrorMessage("Change Signature", it, null, myProject)
            return false
        }
        return showConflicts(conflicts(refUsages.get()), refUsages.get())
    }

    /** What the change cannot do cleanly (see the class comment); everything but [GoChangeSignature.validate]'s refusals allows Refactor Anyway. */
    fun conflicts(usages: Array<out UsageInfo>): MultiMap<PsiElement, String> {
        val conflicts = MultiMap<PsiElement, String>()
        val fn = GoChangeSignature.displayName(target)
        nameClash()?.let { (element, message) -> conflicts.putValue(element, message) }
        if (!nameChanges && !typeChanges) return conflicts
        when (target) {
            is GoMethodSpec -> conflicts.putValue(target, "$fn is an interface method: its implementations and calls through other interfaces are not changed")
            is GoMethodDeclaration -> for (spec in GoImplementations.superMethods(target, GlobalSearchScope.projectScope(myProject))) {
                conflicts.putValue(target, "Method $fn implements ${GoChangeSignature.displayName(spec)}; after the change it no longer does")
            }
        }
        removedParametersUsedInBody().forEach { (def, count) ->
            val times = if (count == 1) "" else " ($count times)"
            conflicts.putValue(def, "Parameter ${def.name} is used in the body$times; the references stay and no longer resolve")
        }
        var resultUses = 0
        for (usage in usages) {
            val element = usage.element ?: continue
            val site = GoParameterRemoval.callSiteOf(element, target)
            if (site == null) {
                if (typeChanges) conflicts.putValue(element, "Function $fn is used as a value here; its type changes")
                continue
            }
            if (options.results.size != oldResults.size && usesResults(site.call)) resultUses++
            if (!argumentsChange) continue
            val bound = boundArguments(site)
            if (bound == null) {
                conflicts.putValue(site.call, "The arguments of this call of $fn do not map one to one onto its parameters; only its name changes")
                continue
            }
            val kept = options.parameters.mapNotNull { it.oldIndex.takeIf { i -> i >= 0 } }.toSet()
            for ((slot, args) in bound.withIndex()) {
                if (slot !in kept && !args.all(GoParameterRemoval::isPure)) {
                    conflicts.putValue(site.call, "The argument for ${oldParameters[slot].name.ifEmpty { "#" + (slot + 1) }} in this call of $fn has side effects; it will be removed")
                }
            }
            if (!GoChangeSignature.keepsOrder(options) && kept.count { s -> !bound[s].all(GoParameterRemoval::isPure) } > 0 && kept.size > 1) {
                conflicts.putValue(site.call, "The arguments of this call of $fn have side effects; their evaluation order changes")
            }
        }
        if (resultUses > 0) {
            val sites = if (resultUses == 1) "1 call site uses" else "$resultUses call sites use"
            conflicts.putValue(target, "$sites the results of $fn; the calls are not rewritten")
        }
        return conflicts
    }

    /** Another declaration that the new name collides with: a package-level one, a method of the receiver type, a method of the interface. */
    private fun nameClash(): Pair<PsiElement, String>? {
        if (!nameChanges) return null
        val name = options.name
        val file = target.containingFile as? GoFile ?: return null
        val files = file.containingDirectory?.files?.filterIsInstance<GoFile>()?.filter { it.packageName == file.packageName } ?: listOf(file)
        when (target) {
            is GoFunctionDeclaration -> for (f in files) {
                val clash = (f.functions + f.types + f.vars + f.consts).firstOrNull { it !== target && (it as GoNamedElement).name == name } ?: continue
                return clash to "Package ${file.packageName} already declares $name"
            }
            is GoMethodDeclaration -> for (f in files) {
                val clash = f.methods.firstOrNull { it !== target && it.receiverTypeName == target.receiverTypeName && it.name == name } ?: continue
                return clash to "Type ${target.receiverTypeName} already has a method $name"
            }
            is GoMethodSpec -> {
                val clash = PsiTreeUtil.getChildrenOfTypeAsList(target.parent, GoMethodSpec::class.java).firstOrNull { it !== target && it.name == name } ?: return null
                return clash to "Interface ${GoChangeSignature.displayName(target).substringBeforeLast('.')} already has a method $name"
            }
        }
        return null
    }

    /** The definitions of the removed parameters that the body still uses, with the number of uses. */
    private fun removedParametersUsedInBody(): List<Pair<GoParamDefinition, Int>> {
        val decl = target as? GoFunctionOrMethodDeclaration ?: return emptyList()
        val kept = options.parameters.map { it.oldIndex }.toSet()
        return GoParameterRemoval.definitions(signature).mapNotNull { def ->
            if (GoParameterRemoval.slot(signature, def) in kept || def.name == "_") return@mapNotNull null
            val uses = ReferencesSearch.search(def, LocalSearchScope(decl)).findAll().size
            if (uses > 0) def to uses else null
        }
    }

    /** The arguments bound to every old slot of [site], or null when they do not map one to one (`f(g())` with a multi-value `g`). */
    private fun boundArguments(site: GoParameterRemoval.CallSite): List<List<PsiElement>>? =
        (0 until arity).map { GoParameterRemoval.argumentsAt(site, it, arity, variadic) ?: return null }.takeIf { arity > 0 || callFits(site) }

    private fun callFits(site: GoParameterRemoval.CallSite): Boolean = site.call.arguments.size == (if (site.methodExpression) 1 else 0)

    /** Whether the value of [call] is used: not an expression statement, `go` or `defer`. */
    private fun usesResults(call: GoCallExpr): Boolean {
        var e: PsiElement = call
        while (e.parent is GoParenthesesExpr) e = e.parent
        val parent = e.parent
        if (parent?.node?.elementType == GoTypes.GO_STATEMENT || parent?.node?.elementType == GoTypes.DEFER_STATEMENT) return false
        if (parent is GoSimpleStatement) return parent.statement != null
        if (parent is GoLeftHandExprList && parent.parent is GoSimpleStatement) return (parent.parent as GoSimpleStatement).statement != null
        return true
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val edits = LinkedHashMap<PsiFile, Edits>()
        fun edits(file: PsiFile) = edits.getOrPut(file) { Edits(file.text) }
        declarationEdits(edits(target.containingFile))
        for (usage in usages) {
            val element = usage.element ?: continue
            val file = element.containingFile ?: continue
            if (nameChanges) usage.rangeInElement?.let { r -> edits(file).add(r.shiftRight(element.textRange.startOffset)) { options.name } }
            if (!argumentsChange) continue
            val site = GoParameterRemoval.callSiteOf(element, target) ?: continue
            callEdit(site, edits(file))
        }
        val documents = PsiDocumentManager.getInstance(myProject)
        val texts = edits.map { (file, e) -> file to e.result() }
        for ((file, replacements) in texts) {
            val document = documents.getDocument(file) ?: continue
            documents.doPostponedOperationsAndUnblockDocument(document)
            for ((range, text) in replacements.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
            documents.commitDocument(document)
        }
    }

    /** The name, the signature, the renamed parameters' uses in the body and the doc comment's leading name. */
    private fun declarationEdits(edits: Edits) {
        val identifier = (target as? GoFunctionOrMethodDeclaration)?.identifier ?: (target as? GoMethodSpec)?.identifier
        if (nameChanges && identifier != null) {
            edits.add(identifier.textRange) { options.name }
            (target as? GoNamedElement)?.docComment?.let { comment ->
                val text = comment.text
                val prefix = "// $oldName"
                if (text.startsWith(prefix) && text.getOrNull(prefix.length)?.let { Character.isLetterOrDigit(it) || it == '_' } != true) {
                    edits.add(TextRange.from(comment.textRange.startOffset + 3, oldName.length)) { options.name }
                }
            }
        }
        val signature = signature ?: return
        val parameters = signature.parameters
        if (typeChanges) edits.addContainer(signature.textRange) { render ->
            val params = if (parametersChange) GoChangeSignature.parametersText(options.parameters) else render(parameters.textRange)
            val results = if (resultsChange) GoChangeSignature.resultsText(options.results)
            else render(TextRange(parameters.textRange.endOffset, signature.textRange.endOffset))
            params + results
        }
        val decl = target as? GoFunctionOrMethodDeclaration ?: return
        for (p in options.parameters) {
            val old = oldParameters.getOrNull(p.oldIndex) ?: continue
            if (p.name.isEmpty() || p.name == "_" || old.name.isEmpty() || p.name == old.name) continue
            val def = GoParameterRemoval.definitions(signature).firstOrNull { GoParameterRemoval.slot(signature, it) == p.oldIndex } ?: continue
            val body = decl.block ?: continue
            for (ref in ReferencesSearch.search(def, LocalSearchScope(body)).findAll()) edits.add(ref.rangeInElement.shiftRight(ref.element.textRange.startOffset)) { p.name }
        }
    }

    /** The argument list of [site] rebuilt from the new parameter order; nothing when it does not map one to one (a conflict). */
    private fun callEdit(site: GoParameterRemoval.CallSite, edits: Edits) {
        val bound = boundArguments(site) ?: return
        val list = site.call.argumentList ?: return
        val open = list.lparen.textRange.endOffset
        val close = list.rparen?.textRange?.startOffset ?: return
        val all = site.call.arguments
        val spread = list.node.findChildByType(GoTypes.ELLIPSIS) != null
        edits.addContainer(TextRange(open, close)) { render ->
            val parts = ArrayList<String>()
            if (site.methodExpression) parts += render(all[0].textRange)
            for (p in options.parameters) {
                if (p.oldIndex < 0) {
                    if (p.defaultValue.isNotBlank()) parts += p.defaultValue.trim()
                    continue
                }
                val args = bound[p.oldIndex]
                if (args.isEmpty()) continue
                parts += render(TextRange(args.first().textRange.startOffset, args.last().textRange.endOffset))
            }
            val last = options.parameters.lastOrNull()
            val keepsSpread = spread && variadic && last != null && last.oldIndex == arity - 1 && bound[arity - 1].isNotEmpty()
            parts.joinToString(", ") + if (keepsSpread) "..." else ""
        }
    }

    /**
     * Replacements of one file computed before any edit. A container (an argument list, the signature) may hold others (`f(1, f(2, 3))`:
     * the outer argument list holds the inner call's name and arguments); its text is built by [render], which applies the ones inside a
     * range, so only the outermost go to the document. A container is outside a plain edit of the same range (`f(f)` renaming `f`).
     */
    private class Edits(private val text: String) {
        private class Edit(val range: TextRange, val container: Boolean) {
            lateinit var text: () -> String
        }

        private val edits = ArrayList<Edit>()

        fun add(range: TextRange, text: () -> String) {
            if (edits.none { it.range == range && !it.container }) edits += Edit(range, false).also { it.text = text }
        }

        /** A container edit: [build] gets a renderer of the ranges inside it (other edits applied, itself left out). */
        fun addContainer(range: TextRange, build: ((TextRange) -> String) -> String) {
            val edit = Edit(range, true)
            edit.text = { build { r -> render(r, edit) } }
            edits += edit
        }

        private fun inside(outer: TextRange, e: TextRange): Boolean = outer.startOffset <= e.startOffset && e.endOffset <= outer.endOffset &&
            (!e.isEmpty || (outer.startOffset < e.startOffset && e.endOffset < outer.endOffset))

        private fun holds(o: Edit, e: Edit): Boolean = o !== e && inside(o.range, e.range) && (o.range != e.range || o.container && !e.container)

        private fun outermost(among: List<Edit>) = among.filter { e -> among.none { o -> holds(o, e) } }

        /** The text of [range] with the edits inside it applied; a container renders its parts with itself left out. */
        private fun render(range: TextRange, self: Edit): String {
            val sb = StringBuilder()
            var pos = range.startOffset
            for (e in outermost(edits.filter { it !== self && inside(range, it.range) }).sortedBy { it.range.startOffset }) {
                sb.append(text, pos, e.range.startOffset).append(e.text())
                pos = e.range.endOffset
            }
            return sb.append(text, pos, range.endOffset).toString()
        }

        fun result(): List<Pair<TextRange, String>> = outermost(edits).map { it.range to it.text() }
    }
}
