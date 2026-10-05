package io.github.golangsupport.ide.inspections.style

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.inspections.GoInspectionText
import io.github.golangsupport.ide.inspections.GoRenameToFix
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil

/** Name helpers of the naming inspections. */
internal object GoNaming {
    fun isExported(name: String): Boolean = name.firstOrNull()?.isUpperCase() == true

    /** Whether [element] is declared at package level (a top-level func / type / var / const). */
    fun isTopLevel(element: PsiElement): Boolean = GoPsiUtil.functionOwner(element) == null

    /** `func`, `type`, `var`, `const`, `parameter`, `receiver`, `type parameter`, `method`; null for other elements. */
    fun kind(element: PsiElement): String? = when (element) {
        is GoFunctionDeclaration -> "func"
        is GoMethodDeclaration -> "method"
        is GoTypeSpec -> "type"
        is GoVarDefinition -> "var"
        is GoConstDefinition -> "const"
        is GoParamDefinition -> "parameter"
        is GoReceiver -> "receiver"
        is GoTypeParamDefinition -> "type parameter"
        else -> null
    }
}

/**
 * GoLand's "Exported element should have its own declaration" (`GoExportedOwnDeclaration`, golint): a package-level `var A, B int` /
 * `const A, B = 1, 2` that declares an exported name together with others. Reported once per spec, at its first exported name. Fix:
 * split the spec into one per name (offered when the spec has no comments, has a value per name or none, and is not followed in a
 * `const (...)` group by specs that repeat it implicitly).
 */
class GoExportedOwnDeclarationInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val names: List<GoNamedElement> = when (element) {
            is GoVarSpec -> element.varDefinitionList
            is GoConstSpec -> element.constDefinitionList
            else -> return
        }
        if (names.size < 2 || !GoNaming.isTopLevel(element)) return
        val first = names.firstOrNull { it.name?.let(GoNaming::isExported) == true } ?: return
        val kind = if (element is GoVarSpec) "var" else "const"
        val fixes = if (splittable(element)) arrayOf<LocalQuickFix>(SPLIT) else emptyArray()
        holder.registerProblem(first.nameIdentifier ?: first, "Exported $kind ${first.name} should have its own declaration", *fixes)
    }

    companion object {
        private fun parts(spec: PsiElement): Triple<List<GoNamedElement>, PsiElement?, List<PsiElement>>? = when (spec) {
            is GoVarSpec -> Triple(spec.varDefinitionList, spec.type, spec.expressionList)
            is GoConstSpec -> Triple(spec.constDefinitionList, spec.type, spec.expressionList)
            else -> null
        }

        fun splittable(spec: PsiElement): Boolean {
            val (names, _, values) = parts(spec) ?: return false
            if (values.isNotEmpty() && values.size != names.size) return false
            if (PsiTreeUtil.findChildOfType(spec, PsiComment::class.java) != null) return false
            if (spec is GoConstSpec) {
                if (values.isEmpty()) return false
                val group = spec.parent as? GoConstDeclaration ?: return false
                val later = group.constSpecList.dropWhile { it !== spec }.drop(1)
                if (later.firstOrNull()?.expressionList?.isEmpty() == true) return false
            }
            return true
        }

        private val SPLIT = GoEditFix("Split into separate declarations") { element ->
            val spec = PsiTreeUtil.getParentOfType(element, GoVarSpec::class.java, GoConstSpec::class.java) ?: return@GoEditFix null
            if (!splittable(spec)) return@GoEditFix null
            val (names, type, values) = parts(spec)!!
            val lines = names.mapIndexed { i, n -> buildString {
                append(n.name)
                type?.let { append(' ').append(it.text) }
                values.getOrNull(i)?.let { append(" = ").append(it.text) }
            } }
            val declaration = spec.parent
            val grouped = (declaration as? GoVarDeclaration)?.lparen != null || (declaration as? GoConstDeclaration)?.lparen != null
            val text = spec.containingFile.viewProvider.contents
            if (grouped) {
                val indent = GoInspectionText.indentAt(text, spec.textRange.startOffset)
                listOf(GoEditPlan.Edit(spec.textRange.startOffset, spec.textRange.endOffset, lines.joinToString("\n$indent")))
            } else {
                val keyword = if (spec is GoVarSpec) "var" else "const"
                listOf(GoEditPlan.Edit(declaration.textRange.startOffset, declaration.textRange.endOffset, lines.joinToString("\n") { "$keyword $it" }))
            }
        }
    }
}

/**
 * GoLand's "Name starts with a package name" (`GoNameStartsWithPackageName`, golint stutter): an exported package-level func, type,
 * var or const of package `probe` named `ProbeThing` reads `probe.ProbeThing` at the use site. The rest must start with an upper-case
 * letter (`probe.Prober` is fine); `main` packages and `_test.go` files are skipped. Fix: rename to the rest (`Thing`).
 */
class GoNameStartsWithPackageNameInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoNamedElement || element is GoMethodDeclaration || element is GoFieldDefinition) return
        val kind = when (element) {
            is GoFunctionDeclaration, is GoTypeSpec, is GoVarDefinition, is GoConstDefinition -> GoNaming.kind(element)
            else -> null
        } ?: return
        val pkg = file.packageName ?: return
        if (pkg == "main" || file.isTestFile || !GoNaming.isTopLevel(element)) return
        val name = element.name ?: return
        if (!GoNaming.isExported(name) || name.length <= pkg.length || !name.startsWith(pkg, ignoreCase = true)) return
        val rest = name.substring(pkg.length)
        if (!rest[0].isUpperCase()) return
        holder.registerProblem(element.nameIdentifier ?: element,
            "$kind name will be used as $pkg.$name by other packages, and that stutters; consider calling this $rest", GoRenameToFix(rest))
    }
}

/**
 * GoLand's "Receiver has a generic name" (`GoReceiverNames`, golint / revive `receiver-naming`): a receiver named `this` or `self`, a
 * receiver named `_` (omit the name instead), and a receiver whose name differs from the one the first method of the same type in the
 * file uses. Fixes: rename (to the first letter of the type, or to the name used before); remove the `_`.
 */
class GoReceiverNamesInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoMethodDeclaration) return
        val receiver = element.receiver ?: return
        val identifier = receiver.identifier ?: return
        val name = identifier.text
        val typeName = element.receiverTypeName ?: return
        when (name) {
            "this", "self" -> {
                val suggested = previousName(element, file, typeName) ?: typeName.take(1).lowercase()
                holder.registerProblem(identifier, "Receiver name should be a reflection of its identity; don't use generic names such as 'this' or 'self'",
                    GoRenameToFix(suggested))
            }
            "_" -> holder.registerProblem(identifier, "Receiver name should not be an underscore, omit the name if it is unused", REMOVE_UNDERSCORE)
            else -> {
                val previous = previousName(element, file, typeName) ?: return
                if (previous == name) return
                holder.registerProblem(identifier, "Receiver name $name should be consistent with previous receiver name $previous for $typeName", GoRenameToFix(previous))
            }
        }
    }

    /** The receiver name of the first method of [typeName] in [file] before [method]; null when [method] is the first named one. */
    private fun previousName(method: GoMethodDeclaration, file: GoFile, typeName: String): String? =
        file.methods.asSequence().takeWhile { it !== method }.filter { it.receiverTypeName == typeName }
            .firstNotNullOfOrNull { it.receiver?.identifier?.text?.takeUnless { n -> n in GENERIC } }

    private companion object {
        val GENERIC = setOf("this", "self", "_")

        val REMOVE_UNDERSCORE = GoEditFix("Remove the receiver name") { id ->
            val receiver = id.parent as? GoReceiver ?: return@GoEditFix null
            val type = receiver.type ?: return@GoEditFix null
            listOf(GoEditPlan.Edit(id.textRange.startOffset, type.textRange.startOffset, ""))
        }
    }
}

/**
 * GoLand's "Type parameter is declared in lowercase" (`GoTypeParameterInLowerCase`, information level): `func F[t any]()`. Type
 * parameters are conventionally capitalised (`T`, `K`, `Elem`). Fix: rename to the capitalised name.
 */
class GoTypeParameterInLowerCaseInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoTypeParamDefinition) return
        val name = element.name ?: return
        if (!name[0].isLowerCase()) return
        holder.registerProblem(element.nameIdentifier ?: element, "Type parameter '$name' is declared in lowercase", GoRenameToFix(name.replaceFirstChar { it.uppercaseChar() }))
    }
}

/**
 * GoLand's "Unit-specific suffix for 'time.Duration'" (`GoUnitSpecificDurationSuffix`, staticcheck ST1011): a variable, constant,
 * parameter or field of type `time.Duration` named with a unit suffix (`timeoutSeconds`, `delayMs`): a Duration is not a number of
 * seconds. Fix: rename without the suffix.
 */
class GoUnitSpecificDurationSuffixInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoVarDefinition && element !is GoConstDefinition && element !is GoParamDefinition && element !is GoFieldDefinition) return
        val named = element as GoNamedElement
        val name = named.name ?: return
        val suffix = SUFFIXES.firstOrNull { name.endsWith(it) && name.length > it.length } ?: return
        val type = GoSemanticService.getInstance(file.project).declarationType(named)
        if (!GoAnalysisPsi.isNamed(type, "time", "Duration")) return
        val kind = if (element is GoFieldDefinition) "field" else GoNaming.kind(element) ?: "var"
        val rest = name.removeSuffix(suffix)
        val fixes = if (rest.isNotEmpty() && rest != "_") arrayOf<LocalQuickFix>(GoRenameToFix(rest)) else emptyArray()
        holder.registerProblem(named.nameIdentifier ?: named, "$kind $name is of type time.Duration; don't use unit-specific suffix \"$suffix\"", *fixes)
    }

    private companion object {
        // staticcheck ST1011's list.
        val SUFFIXES = listOf("Sec", "Secs", "Seconds", "Msec", "Msecs", "Milli", "Millis", "Milliseconds", "Usec", "Usecs", "Microseconds", "MS", "Ms")
    }
}

/**
 * GoLand's "Usage of Snake_Case" (`GoSnakeCaseUsage`, golint): a function, method, type, variable, constant, parameter, receiver or type
 * parameter whose name has an underscore inside (`max_size`, `Parse_URL`). Leading / trailing underscores, `_`, names without
 * lower-case letters (`MAX_SIZE`), `Test…_…` / `Benchmark…` / `Example…` / `Fuzz…` functions of `_test.go` files, cgo names (`C_x` /
 * `_Cfunc`) and generated files are skipped; struct fields are not checked (they often mirror external schemas). Fix: rename to camelCase.
 */
class GoSnakeCaseUsageInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoNamedElement) return
        val kind = GoNaming.kind(element) ?: return
        val name = element.name ?: return
        val core = name.trim('_')
        if (!core.contains('_') || core.none { it.isLowerCase() } || name.startsWith("C_")) return
        if (file.isTestFile && (element is GoFunctionDeclaration || element is GoMethodDeclaration) && TEST_PREFIXES.any { name.startsWith(it) }) return
        if (GoAnalysisScope.isGenerated(file)) return
        val camel = GoInspectionText.camelCase(name)
        holder.registerProblem(element.nameIdentifier ?: element, "Don't use underscores in Go names; $kind $name should be $camel",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING, GoRenameToFix(camel))
    }

    private companion object {
        val TEST_PREFIXES = listOf("Test", "Benchmark", "Example", "Fuzz")
    }
}
