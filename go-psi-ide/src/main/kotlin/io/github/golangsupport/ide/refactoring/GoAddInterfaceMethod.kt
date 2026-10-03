package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.ui.UsageViewDescriptorAdapter
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.intentions.GoCreateText
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.intentions.GoImplementStubs
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.lang.psi.GoInterfaceType as GoInterfaceTypePsi

/**
 * What "Add Method to Interface" adds: the method [name] with its [parameters] (an empty name is an unnamed parameter, `...T` a variadic
 * one) and [results]; with [delegate], a type that holds the interface in a struct field gets a stub that calls the field instead of
 * panicking. [signature] is the interface element as it is written: `Get(ctx context.Context, id string) (Item, error)`.
 */
data class GoAddMethodOptions(
    val name: String, val parameters: List<GoChangeParameter> = emptyList(), val results: List<GoChangeResult> = emptyList(), val delegate: Boolean = true,
) {
    val signature: String get() = name.trim() + GoChangeSignature.parametersText(parameters) + GoChangeSignature.resultsText(results)

    companion object {
        /**
         * The options from one line of Go (`Name(a, b int, opts ...Opt) (n int, err error)`): `a, b int` gives two parameters typed `int`.
         * Text that is not `Name(...)...` becomes a name, so validation refuses it.
         */
        @JvmStatic
        @JvmOverloads
        fun of(signature: String, delegate: Boolean = true): GoAddMethodOptions {
            val text = signature.trim()
            val open = text.indexOf('(')
            val close = if (open > 0) closing(text, open) else -1
            if (close < 0) return GoAddMethodOptions(text, delegate = delegate)
            val parameters = items(text.substring(open + 1, close)).map { (n, t) -> GoChangeParameter(n, t) }
            var rest = text.substring(close + 1).trim()
            if (rest.startsWith("(") && closing(rest, 0) == rest.length - 1) rest = rest.substring(1, rest.length - 1)
            return GoAddMethodOptions(text.substring(0, open).trim(), parameters, items(rest).map { (n, t) -> GoChangeResult(n, t) }, delegate)
        }

        /** `a, b int, c string` as (name, type) pairs; a lone word before a named item is a name taking that item's type (Go grouping). */
        private fun items(text: String): List<Pair<String, String>> {
            val raw = GoChangeSignature.splitTopLevel(text).map { item ->
                val s = item.trim()
                val space = s.indexOfFirst { it == ' ' || it == '\t' }
                val first = if (space > 0) s.substring(0, space) else ""
                if (space > 0 && GoNamesValidator.isValidIdentifier(first)) first to s.substring(space + 1).trim() else "" to s
            }
            if (raw.none { it.first.isNotEmpty() }) return raw
            val result = raw.toMutableList()
            for (i in result.indices.reversed()) {
                if (result[i].first.isEmpty() && GoNamesValidator.isValidIdentifier(result[i].second)) {
                    result[i] = result[i].second to (result.getOrNull(i + 1)?.second ?: "")
                }
            }
            return result
        }

        private fun closing(s: String, open: Int): Int {
            var depth = 0
            for (i in open until s.length) {
                if (s[i] == '(') depth++ else if (s[i] == ')' && --depth == 0) return i
            }
            return -1
        }
    }
}

/** A type the new method reaches: its [name], [file], and [note] for the list in the dialog (a wrapper's field, why it is skipped). */
data class GoAddMethodTarget(val name: String, val file: String, val note: String)

/** What the dialog of Add Method to Interface reads once, in the background: the interface, its method names and the types to update. */
data class GoAddMethodContext(val interfaceName: String, val methods: Set<String>, val targets: List<GoAddMethodTarget>)

/** The dialog field a validation problem points at. */
enum class GoAddMethodField { NAME, PARAMETERS, RESULTS }

/** Why the method cannot be added: [message] for the dialog, the [field] to mark. A blank name is [silent]: OK is off but nothing is shown yet. */
data class GoAddMethodProblem(val message: String, val field: GoAddMethodField, val silent: Boolean = false)

/**
 * The pure model of the Add Method dialog: validation of the name, parameters and results against the interface [interfaceName] with
 * [methods], the exported-name hint and the preview. The types themselves are checked by the Go parser ([GoAddInterfaceMethod.typeProblem]).
 */
class GoAddMethodModel(private val interfaceName: String, private val methods: Set<String>) {

    fun problem(options: GoAddMethodOptions): GoAddMethodProblem? {
        val name = options.name.trim()
        if (name.isEmpty()) return GoAddMethodProblem("Enter the method name", GoAddMethodField.NAME, silent = true)
        if (!GoNamesValidator.isValidIdentifier(name) || name == "_") return GoAddMethodProblem("'$name' is not a valid method name", GoAddMethodField.NAME)
        if (name in methods) return GoAddMethodProblem("Interface $interfaceName already has a method $name", GoAddMethodField.NAME)
        val params = options.parameters
        for ((i, p) in params.withIndex()) {
            if (p.name.isNotEmpty() && p.name != "_" && !GoNamesValidator.isValidIdentifier(p.name)) return params("'${p.name}' is not a valid parameter name")
            if (p.type.isBlank() || p.type.trim() == "...") return params("Parameter ${p.name.ifEmpty { "#" + (i + 1) }} has no type")
        }
        if (params.any { it.name.isEmpty() } && params.any { it.name.isNotEmpty() }) return params("Either every parameter has a name or none has")
        if (params.dropLast(1).any { it.isVariadic }) return params("Only the last parameter can be variadic")
        val results = options.results
        for ((i, r) in results.withIndex()) {
            if (r.name.isNotEmpty() && r.name != "_" && !GoNamesValidator.isValidIdentifier(r.name)) return results("'${r.name}' is not a valid result name")
            if (r.type.isBlank()) return results("Result ${r.name.ifEmpty { "#" + (i + 1) }} has no type")
            if (r.type.trimStart().startsWith("...")) return results("A result cannot be variadic")
        }
        if (results.any { it.name.isEmpty() } && results.any { it.name.isNotEmpty() }) return results("Either every result has a name or none has")
        val names = (params.map { it.name } + results.map { it.name }).filter { it.isNotEmpty() && it != "_" }
        names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { dup ->
            return if (params.count { it.name == dup.key } > 1) params("Parameter ${dup.key} is declared twice") else results("${dup.key} is declared twice")
        }
        return null
    }

    /** An unexported method in an exported interface: legal, but no type outside the package can implement it any more. */
    fun hint(name: String): String? {
        val n = name.trim()
        if (n.isEmpty() || !isExported(interfaceName) || isExported(n) || !GoNamesValidator.isValidIdentifier(n)) return null
        return "$n is unexported: types outside the package will no longer be able to implement $interfaceName"
    }

    /** The interface element as it will read; `?` stands for a missing name. */
    fun preview(options: GoAddMethodOptions): String = options.copy(name = options.name.trim().ifEmpty { "?" }).signature

    private fun params(message: String) = GoAddMethodProblem(message, GoAddMethodField.PARAMETERS)

    private fun results(message: String) = GoAddMethodProblem(message, GoAddMethodField.RESULTS)

    companion object {
        fun isExported(name: String): Boolean = name.firstOrNull()?.isUpperCase() == true

        /** A row added to the parameters: `pN string` (unnamed when the others are), before a variadic parameter. */
        fun newParameter(rows: List<GoChangeParameter>): Pair<Int, GoChangeParameter> {
            val used = rows.map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "p$it" }.first { it !in used }
            val index = rows.indexOfFirst { it.isVariadic }.takeIf { it >= 0 } ?: rows.size
            return index to GoChangeParameter(if (rows.isNotEmpty() && rows.all { it.name.isEmpty() }) "" else name, "string")
        }

        /** A row added to the results: `error` (named `err` when the others are named), last. */
        fun newResult(rows: List<GoChangeResult>): Pair<Int, GoChangeResult> {
            val used = rows.map { it.name }.toSet()
            val name = (sequenceOf("err") + generateSequence(1) { it + 1 }.map { "r$it" }).first { it !in used }
            return rows.size to GoChangeResult(if (rows.isNotEmpty() && rows.all { it.name.isNotEmpty() }) name else "", "error")
        }
    }
}

/** The pure part of Add Method to Interface: the interface at the caret and the method typed in the dialog. */
object GoAddInterfaceMethod {

    /** The package-level, non-generic interface type spec whose name or interface body holds [element]. */
    fun interfaceAt(element: PsiElement): GoTypeSpec? {
        val spec = PsiTreeUtil.getParentOfType(element, GoTypeSpec::class.java, false) ?: return null
        if (spec.type !is GoInterfaceTypePsi || spec.typeParameters != null || (spec.parent as? GoTypeDeclaration)?.parent !is GoFile) return null
        val onName = spec.identifier.textRange.contains(element.textRange)
        return spec.takeIf { onName || PsiTreeUtil.isAncestor(spec.type, element, false) }
    }

    /** [text] parsed as one interface method (`Name(params) results`), or null when it is not exactly that. */
    fun parse(project: Project, text: String): GoMethodSpec? {
        if (text.isBlank() || text.contains('\n') || text.contains(';')) return null
        val file = PsiFileFactory.getInstance(project).createFileFromText("add_method.go", GoLanguage, "package p\n\ntype _ interface {\n\t${text.trim()}\n}\n")
        if (PsiTreeUtil.hasErrorElements(file) || PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return null
        return PsiTreeUtil.findChildrenOfType(file, GoMethodSpec::class.java).singleOrNull()
    }

    /** The method names of [iface], embedded interfaces included. */
    fun methodsOf(iface: GoTypeSpec): Set<String> = GoImplementations.interfaceOf(iface)?.allMethods.orEmpty().map { it.name }.toSet()

    /** The dialog's view of [iface] (read action): its methods and the implementing types with what happens to each. */
    fun context(iface: GoTypeSpec): GoAddMethodContext {
        val targets = GoImplementations.implementingTypes(iface, GlobalSearchScope.allScope(iface.project)).map { type ->
            val note = when {
                !GoImplementations.isInProject(type) -> "outside the project: not changed"
                GoSignatureHierarchy.isGenerated(type.containingFile) -> "generated: not changed"
                else -> wrappedField(type, iface)?.let { "wrapper: field $it" } ?: ""
            }
            GoAddMethodTarget(type.name ?: "?", type.containingFile.name, note)
        }
        return GoAddMethodContext(iface.name ?: "?", methodsOf(iface), targets.sortedWith(compareBy({ it.file }, { it.name })))
    }

    /** The parameter or result whose type the Go parser rejects, or the whole method when it does; null when [options] parse. */
    fun typeProblem(project: Project, options: GoAddMethodOptions): GoAddMethodProblem? {
        if (parse(project, options.signature) != null) return null
        for ((i, p) in options.parameters.withIndex()) {
            if (parse(project, "M(_ ${p.type.trim()})") == null) {
                return GoAddMethodProblem("Parameter ${p.name.ifEmpty { "#" + (i + 1) }}: '${p.type.trim()}' is not a Go type", GoAddMethodField.PARAMETERS)
            }
        }
        for ((i, r) in options.results.withIndex()) {
            if (parse(project, "M() (${r.type.trim()})") == null) {
                return GoAddMethodProblem("Result ${r.name.ifEmpty { "#" + (i + 1) }}: '${r.type.trim()}' is not a Go type", GoAddMethodField.RESULTS)
            }
        }
        return GoAddMethodProblem("'${options.signature}' is not a valid method", GoAddMethodField.NAME)
    }

    /** Why [options] cannot be added to [iface], or null: an invalid name, parameter or result, a method the interface already has, a type that does not parse. */
    fun validate(iface: GoTypeSpec, options: GoAddMethodOptions): String? =
        (GoAddMethodModel(iface.name ?: "?", methodsOf(iface)).problem(options) ?: typeProblem(iface.project, options))?.message

    /** The name of a non-embedded struct field of [type] whose type is the interface [spec] (the wrapper's next layer). */
    fun wrappedField(type: GoTypeSpec, spec: GoTypeSpec): String? {
        val named = GoSemanticService.getInstance(type.project).declarationType(type) as? GoNamedType ?: return null
        val struct = named.underlying() as? GoStructType ?: return null
        return struct.fields.firstOrNull { f ->
            !f.embedded && (f.type as? GoNamedType)?.declaration?.let { it.name == spec.name && it.containingFile == spec.containingFile } == true
        }?.name
    }
}

/**
 * Add Method to Interface: the method goes into the interface (imports for the packages its types name), and every project type
 * that implemented the interface gets a stub of it ([GoImplementStubs]: the receiver as the type's methods write it, the body
 * `panic("not implemented")`); a type holding the interface in a struct field gets `return w.field.Name(args)` instead when
 * [GoAddMethodOptions.delegate] is on. A type embedding the interface needs nothing (the method is promoted). Types that already have
 * a field or method of that name, types in generated files and implementations outside the project are left as they are and reported.
 */
class GoAddInterfaceMethodProcessor(project: Project, private val iface: GoTypeSpec, private val options: GoAddMethodOptions) : BaseRefactoringProcessor(project) {

    private val name get() = options.name.trim()

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptorAdapter() {
        override fun getElements(): Array<PsiElement> = arrayOf(iface)

        override fun getProcessedElementsHeader(): String = "Add a method to ${iface.name}"
    }

    override fun getCommandName(): String = "Add Method to Interface ${iface.name}"

    /** The types implementing the interface before the change, inside the project and out. */
    override fun findUsages(): Array<UsageInfo> =
        GoImplementations.implementingTypes(iface, GlobalSearchScope.allScope(myProject)).map { UsageInfo(it) }.toTypedArray()

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        GoAddInterfaceMethod.validate(iface, options)?.let {
            CommonRefactoringUtil.showErrorMessage(TITLE, it, null, myProject)
            return false
        }
        return showConflicts(conflicts(refUsages.get()), refUsages.get())
    }

    fun conflicts(usages: Array<out UsageInfo>): MultiMap<PsiElement, String> {
        val conflicts = MultiMap<PsiElement, String>()
        for (type in usages.mapNotNull { it.element as? GoTypeSpec }) skipReason(type)?.let { conflicts.putValue(type, it) }
        return conflicts
    }

    /** Why [type] gets no stub, or null. */
    private fun skipReason(type: GoTypeSpec): String? {
        if (!GoImplementations.isInProject(type)) return "Type ${type.name} outside the project implements ${iface.name}; after the change it no longer does"
        if (GoSignatureHierarchy.isGenerated(type.containingFile)) return "Type ${type.name} is in a generated file (${type.containingFile.name}); no stub is added: run go generate"
        val named = GoSemanticService.getInstance(myProject).declarationType(type) as? GoNamedType ?: return null
        if (GoSemanticService.getInstance(myProject).lookupFieldOrMethod(GoPointerType(named), name, type.containingFile as? GoFile) != null) {
            return "Type ${type.name} already has a method or field $name; no stub is added"
        }
        return null
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val pointers = SmartPointerManager.getInstance(myProject)
        val types = usages.mapNotNull { it.element as? GoTypeSpec }.filter { skipReason(it) == null }.map(pointers::createSmartPsiElementPointer)
        val ifacePointer = pointers.createSmartPsiElementPointer(iface)
        addSpec()
        val spec = ifacePointer.element ?: return
        val ifaceType = GoImplementations.interfaceOf(spec) ?: return
        val edits = LinkedHashMap<GoFile, Pair<MutableList<GoEditPlan.Edit>, MutableSet<String>>>()
        for (type in types.mapNotNull { it.element }) {
            val stubs = GoImplementStubs.compute(type, ifaceType, null) ?: continue
            val field = if (options.delegate) GoAddInterfaceMethod.wrappedField(type, spec) else null
            val edit = field?.let { f -> GoCreateText.afterMethods(type, stubs.stubs.joinToString("\n\n") { delegating(it, f) }) } ?: stubs.edit
            val entry = edits.getOrPut(stubs.file) { ArrayList<GoEditPlan.Edit>() to LinkedHashSet() }
            entry.first += edit
            entry.second += stubs.imports
        }
        for ((file, entry) in edits) {
            val document = PsiDocumentManager.getInstance(myProject).getDocument(file) ?: continue
            PsiDocumentManager.getInstance(myProject).doPostponedOperationsAndUnblockDocument(document)
            for (edit in entry.first.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
            PsiDocumentManager.getInstance(myProject).commitDocument(document)
            for (path in entry.second) GoImportInserter.addImport(file, document, path)
            PsiDocumentManager.getInstance(myProject).commitDocument(document)
        }
    }

    /** The method as the last element of the interface, on its own line (a one-line `interface{}` is opened up), with imports. */
    private fun addSpec() {
        val file = iface.containingFile as? GoFile ?: return
        val type = iface.type as? GoInterfaceTypePsi ?: return
        val documents = PsiDocumentManager.getInstance(myProject)
        val document = documents.getDocument(file) ?: return
        documents.doPostponedOperationsAndUnblockDocument(document)
        val text = document.charsSequence
        val lbrace = type.node.findChildByType(GoTypes.LBRACE)?.textRange ?: return
        val rbrace = type.node.findChildByType(GoTypes.RBRACE)?.textRange ?: return
        val lineStart = text.lastIndexOf('\n', iface.textRange.startOffset - 1) + 1
        val indent = text.subSequence(lineStart, iface.textRange.startOffset).takeWhile { it == ' ' || it == '\t' }.toString()
        val element = "$indent\t${options.signature.trim()}\n"
        var before = rbrace.startOffset
        while (before > lbrace.endOffset && (text[before - 1] == ' ' || text[before - 1] == '\t')) before--
        if (before > lbrace.endOffset && text[before - 1] == '\n') document.insertString(before, element)
        else {
            val body = text.subSequence(lbrace.endOffset, rbrace.startOffset).toString().trim().trimEnd(';')
            val existing = if (body.isEmpty()) "" else body.split(';').joinToString("") { "$indent\t${it.trim()}\n" }
            document.replaceString(lbrace.startOffset, rbrace.endOffset, "{\n$existing$element$indent}")
        }
        documents.commitDocument(document)
        GoMissingImports.add(file, options.signature)
    }

    /** [stub] with its `panic` body replaced by `return recv.field.Name(args)`; as it is when a parameter is unnamed or `_`. */
    private fun delegating(stub: String, field: String): String {
        val receiver = RECEIVER.find(stub)?.groupValues?.get(1) ?: return stub
        val open = stub.indexOf("$name(", stub.indexOf(')') + 1).takeIf { it >= 0 }?.plus(name.length) ?: return stub
        val close = closing(stub, open).takeIf { it > 0 } ?: return stub
        val args = GoChangeSignature.splitTopLevel(stub.substring(open + 1, close)).map { p ->
            val parts = p.trim().split(' ', limit = 2)
            if (parts.size < 2 || parts[0] == "_") return stub
            parts[0] + if (parts[1].trim().startsWith("...")) "..." else ""
        }
        val results = stub.substring(close + 1, stub.lastIndexOf(" {\n")).isNotBlank()
        val call = "$receiver.$field.$name(${args.joinToString(", ")})"
        return stub.replace(GoCreateText.BODY, "\t" + (if (results) "return " else "") + call)
    }

    private fun closing(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '(') depth++ else if (s[i] == ')' && --depth == 0) return i
        }
        return -1
    }

    companion object {
        const val TITLE: String = "Add Method to Interface"
        private val RECEIVER = Regex("""^func \((\w+) """)
    }
}
