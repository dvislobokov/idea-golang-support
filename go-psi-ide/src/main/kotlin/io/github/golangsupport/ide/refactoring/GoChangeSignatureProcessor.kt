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
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoStructType

/**
 * Change Signature of a function, method or interface method spec: the declaration's name, parameters and results are regenerated
 * (receiver, type parameters and doc comment kept; parameters as `a int, b int`), every reference gets the new name, and every call
 * its arguments mapped from the old slots to the new ones (defaults for new parameters; `T.M(recv, …)` keeps the receiver first; a
 * spread `f(xs...)` keeps its `...` on the variadic parameter, which stays last). Results are not followed into call sites nor
 * `return` statements: a changed result count is reported when calls use the results.
 *
 * An interface method or a method implementing one changes with its whole [GoSignatureHierarchy] (the default): every spec and
 * implementation gets the new list by position, keeping its own names of the unchanged parameters (a renamed one is renamed in each
 * body), and the calls through any of them (the delegation `s.next.Get(…)` of a wrapper included) are mapped alike. Declarations in
 * generated files and outside the project are left as they are and reported. With [GoChangeSignatureOptions.hierarchy] off the
 * declaration changes alone, reported as a conflict.
 */
class GoChangeSignatureProcessor(project: Project, private val target: PsiElement, private val options: GoChangeSignatureOptions) : BaseRefactoringProcessor(project) {

    /** Runs at the end of [performRefactoring], in the same command and write action (Introduce Parameter replaces the expression there). */
    var afterRefactoring: (() -> Unit)? = null

    /** The command name when another refactoring drives this one (Introduce Parameter). */
    var commandTitle: String? = null

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

    /** Computed on first use (in [findUsages], under a read action with progress), not in the constructor (the dialog's EDT). */
    private val hierarchy: GoSignatureHierarchy by lazy { if (options.hierarchy) GoSignatureHierarchy.of(target) else GoSignatureHierarchy.single(target) }

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptorAdapter() {
        override fun getElements(): Array<PsiElement> = hierarchy.members.toTypedArray()

        override fun getProcessedElementsHeader(): String = "Change signature of ${GoChangeSignature.displayName(target)}"
    }

    override fun getCommandName(): String = commandTitle ?: "Change Signature of ${GoChangeSignature.displayName(target)}"

    /** The references to every declaration of the hierarchy, each once; none inside generated files (regenerated, not edited). */
    override fun findUsages(): Array<UsageInfo> {
        val seen = HashSet<Pair<PsiElement, TextRange?>>()
        val result = ArrayList<UsageInfo>()
        for (decl in hierarchy.all) {
            for (ref in ReferencesSearch.search(decl, decl.useScope).findAll()) {
                val element = ref.element
                if (!GoParameterRemoval.isCodeReference(element) || GoSignatureHierarchy.isGenerated(element.containingFile)) continue
                val info = UsageInfo(ref)
                if (seen.add(element to info.rangeInElement)) result += info
            }
        }
        return result.toTypedArray()
    }

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
        for (member in hierarchy.members) nameClash(member)?.let { (element, message) -> conflicts.putValue(element, message) }
        if (!nameChanges && !typeChanges) return conflicts
        if (!options.hierarchy) when (target) {
            is GoMethodSpec -> conflicts.putValue(target, "$fn is an interface method: its implementations and calls through other interfaces are not changed")
            is GoMethodDeclaration -> for (spec in GoImplementations.superMethods(target, GlobalSearchScope.projectScope(myProject))) {
                conflicts.putValue(target, "Method $fn implements ${GoChangeSignature.displayName(spec)}; after the change it no longer does")
            }
        }
        hierarchyConflicts(conflicts)
        val dropped = droppedArguments(usages)
        for (member in hierarchy.members) removedParametersUsedInBody(member, dropped).forEach { (def, count) ->
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

    /** Generated and outside declarations of the hierarchy, and project types used as its interfaces without implementing them. */
    private fun hierarchyConflicts(conflicts: MultiMap<PsiElement, String>) {
        if (!options.hierarchy) return
        for (e in hierarchy.generated) {
            conflicts.putValue(e, "${GoChangeSignature.displayName(e)} is in a generated file (${e.containingFile.name}); it is not changed: run go generate")
        }
        for (e in hierarchy.outside) {
            conflicts.putValue(target, "${GoChangeSignature.displayName(e)} is outside the project and cannot be changed; it no longer matches after the change")
        }
        val interfaces = hierarchy.all.filterIsInstance<GoMethodSpec>().mapNotNull(GoImplementations::interfaceSpecOf).toSet()
        if (interfaces.isEmpty() || !typeChanges) return
        val newTypes = options.parameters.map { it.type.trim() } to options.results.map { it.type.trim() }
        val keeps = { m: GoMethodDeclaration ->
            m.name == options.name && (GoChangeSignature.parametersOf(m.signature).map { it.type.trim() } to GoChangeSignature.resultsOf(m.signature).map { it.type.trim() }) == newTypes
        }
        for ((element, message) in GoSignatureHierarchy.misfits(interfaces, oldName, 0..MAX_ARITY, hierarchy.all + hierarchy.outside, keeps)) {
            conflicts.putValue(element, message)
        }
    }

    /**
     * Another declaration that the new name of [member] collides with: a package-level one, a method or field of the receiver type,
     * a method of the interface.
     */
    private fun nameClash(member: PsiElement): Pair<PsiElement, String>? {
        if (!nameChanges) return null
        val name = options.name
        val file = member.containingFile as? GoFile ?: return null
        val files = file.containingDirectory?.files?.filterIsInstance<GoFile>()?.filter { it.packageName == file.packageName } ?: listOf(file)
        when (member) {
            is GoFunctionDeclaration -> for (f in files) {
                val clash = (f.functions + f.types + f.vars + f.consts).firstOrNull { it !== member && (it as GoNamedElement).name == name } ?: continue
                return clash to "Package ${file.packageName} already declares $name"
            }
            is GoMethodDeclaration -> {
                for (f in files) {
                    val clash = f.methods.firstOrNull { it !== member && it.receiverTypeName == member.receiverTypeName && it.name == name } ?: continue
                    return clash to "Type ${member.receiverTypeName} already has a method $name"
                }
                val spec = GoImplementations.receiverTypeSpec(member) ?: return null
                val struct = (GoSemanticService.getInstance(myProject).declarationType(spec) as? GoNamedType)?.underlying() as? GoStructType ?: return null
                val field = struct.field(name) ?: return null
                return (field.declaration ?: member) to "Type ${spec.name} already has a field $name"
            }
            is GoMethodSpec -> {
                val clash = PsiTreeUtil.getChildrenOfTypeAsList(member.parent, GoMethodSpec::class.java).firstOrNull { it !== member && it.name == name } ?: return null
                return clash to "Interface ${GoChangeSignature.displayName(member).substringBeforeLast('.')} already has a method $name"
            }
        }
        return null
    }

    /**
     * The definitions of the removed parameters that the body of [member] still uses, with the number of uses; a use inside an
     * argument the change drops ([dropped], the delegation `s.next.Get(ctx, id)` losing `ctx`) goes with it and does not count.
     */
    private fun removedParametersUsedInBody(member: PsiElement, dropped: List<PsiElement>): List<Pair<GoParamDefinition, Int>> {
        val decl = member as? GoFunctionOrMethodDeclaration ?: return emptyList()
        val signature = decl.signature
        val kept = options.parameters.map { it.oldIndex }.toSet()
        return GoParameterRemoval.definitions(signature).mapNotNull { def ->
            if (GoParameterRemoval.slot(signature, def) in kept || def.name == "_") return@mapNotNull null
            val uses = ReferencesSearch.search(def, LocalSearchScope(decl)).findAll().count { ref -> dropped.none { PsiTreeUtil.isAncestor(it, ref.element, false) } }
            if (uses > 0) def to uses else null
        }
    }

    /** The arguments of the calls among [usages] bound to removed parameters. */
    private fun droppedArguments(usages: Array<out UsageInfo>): List<PsiElement> {
        if (!argumentsChange) return emptyList()
        val kept = options.parameters.map { it.oldIndex }.toSet()
        return usages.flatMap { usage ->
            val site = usage.element?.let { GoParameterRemoval.callSiteOf(it, target) } ?: return@flatMap emptyList()
            val bound = boundArguments(site) ?: return@flatMap emptyList()
            bound.withIndex().filter { it.index !in kept }.flatMap { it.value }
        }
    }

    /**
     * The new parameters' names in [site] when it is a delegation inside a declaration of the hierarchy (`s.next.Get(ctx, id)` in the
     * wrapper's `Get`: every kept argument is the caller's own parameter of that slot): the caller's names, so a new parameter is
     * passed on rather than replaced by its default. Null for any other call.
     */
    private fun delegation(site: GoParameterRemoval.CallSite, bound: List<List<PsiElement>>): List<GoChangeParameter>? {
        if (!options.hierarchy || options.parameters.none { it.oldIndex < 0 }) return null
        val caller = PsiTreeUtil.getParentOfType(site.call, GoFunctionOrMethodDeclaration::class.java) ?: return null
        if (caller !in hierarchy.members) return null
        val defs = GoParameterRemoval.definitions(caller.signature)
        for ((slot, args) in bound.withIndex()) {
            val def = defs.firstOrNull { GoParameterRemoval.slot(caller.signature, it) == slot } ?: return null
            val arg = args.singleOrNull() as? GoReferenceExpression ?: return null
            if (arg.text != def.name || arg.reference?.resolve() != def) return null
        }
        return parametersFor(caller).takeIf { ps -> ps.none { it.oldIndex < 0 && (it.name.isEmpty() || it.name == "_") } }
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
        // The text each file gets that may name packages: the dialog completes them without importing (see GoMissingImports).
        val written = LinkedHashMap<PsiFile, StringBuilder>()
        fun written(file: PsiFile) = written.getOrPut(file) { StringBuilder() }
        for (member in hierarchy.members) {
            declarationEdits(member, edits(member.containingFile))
            if (parametersChange) parametersFor(member).forEach { written(member.containingFile).append(it.type).append(' ') }
            if (resultsChange) options.results.forEach { written(member.containingFile).append(it.type).append(' ') }
        }
        val defaults = options.parameters.filter { it.oldIndex < 0 }.joinToString(" ") { it.defaultValue }
        for (usage in usages) {
            val element = usage.element ?: continue
            val file = element.containingFile ?: continue
            if (nameChanges) usage.rangeInElement?.let { r -> edits(file).add(r.shiftRight(element.textRange.startOffset)) { options.name } }
            if (!argumentsChange) continue
            val site = GoParameterRemoval.callSiteOf(element, target) ?: continue
            callEdit(site, edits(file))
            written(file).append(defaults).append(' ')
        }
        val documents = PsiDocumentManager.getInstance(myProject)
        val texts = edits.map { (file, e) -> file to e.result() }
        for ((file, replacements) in texts) {
            val document = documents.getDocument(file) ?: continue
            documents.doPostponedOperationsAndUnblockDocument(document)
            for ((range, text) in replacements.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
            documents.commitDocument(document)
        }
        for ((file, text) in written) if (file is GoFile && text.isNotBlank()) GoMissingImports.add(file, text.toString())
        afterRefactoring?.invoke()
    }

    /**
     * The new parameters as [member] writes them: by position, its own name and type spelling for a parameter whose name or type the
     * options keep (implementations name parameters as they like; another package qualifies types its own way). A list that would
     * mix named and unnamed parameters goes unnamed when the member's was, else names the new ones `_`.
     */
    private fun parametersFor(member: PsiElement): List<GoChangeParameter> {
        if (member === target) return options.parameters
        val own = GoChangeSignature.parametersOf(GoChangeSignature.signatureOf(member))
        val mapped = options.parameters.map { p ->
            val base = oldParameters.getOrNull(p.oldIndex)
            val mine = own.getOrNull(p.oldIndex)
            if (base == null || mine == null) p
            else p.copy(name = if (p.name != base.name) p.name else mine.name, type = if (p.type.trim() != base.type.trim()) p.type else mine.type)
        }
        if (mapped.none { it.name.isEmpty() } || mapped.all { it.name.isEmpty() }) return mapped
        return if (own.isNotEmpty() && own.all { it.name.isEmpty() }) mapped.map { it.copy(name = "") } else mapped.map { if (it.name.isEmpty()) it.copy(name = "_") else it }
    }

    /** The name, the signature, the renamed parameters' uses in the body and the doc comment's leading name of [member]. */
    private fun declarationEdits(member: PsiElement, edits: Edits) {
        val identifier = (member as? GoFunctionOrMethodDeclaration)?.identifier ?: (member as? GoMethodSpec)?.identifier
        if (nameChanges && identifier != null) {
            edits.add(identifier.textRange) { options.name }
            (member as? GoNamedElement)?.docComment?.let { comment ->
                val text = comment.text
                val prefix = "// $oldName"
                if (text.startsWith(prefix) && text.getOrNull(prefix.length)?.let { Character.isLetterOrDigit(it) || it == '_' } != true) {
                    edits.add(TextRange.from(comment.textRange.startOffset + 3, oldName.length)) { options.name }
                }
            }
        }
        val signature = GoChangeSignature.signatureOf(member) ?: return
        val own = GoChangeSignature.parametersOf(signature)
        val parameters = parametersFor(member)
        if (typeChanges) edits.addContainer(signature.textRange) { render ->
            val params = if (parametersChange) GoChangeSignature.parametersText(parameters) else render(signature.parameters.textRange)
            val results = if (resultsChange) GoChangeSignature.resultsText(options.results)
            else render(TextRange(signature.parameters.textRange.endOffset, signature.textRange.endOffset))
            params + results
        }
        val decl = member as? GoFunctionOrMethodDeclaration ?: return
        for (p in parameters) {
            val old = own.getOrNull(p.oldIndex) ?: continue
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
        val forwarded = delegation(site, bound)
        edits.addContainer(TextRange(open, close)) { render ->
            val parts = ArrayList<String>()
            if (site.methodExpression) parts += render(all[0].textRange)
            for ((i, p) in options.parameters.withIndex()) {
                if (p.oldIndex < 0) {
                    val value = forwarded?.getOrNull(i)?.name ?: p.defaultValue.trim()
                    if (value.isNotBlank()) parts += value + if (forwarded != null && p.isVariadic) "..." else ""
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

    private companion object {
        /** Methods of a same-named misfit are looked up by `name/arity` up to this many parameters. */
        const val MAX_ARITY = 12
    }
}
