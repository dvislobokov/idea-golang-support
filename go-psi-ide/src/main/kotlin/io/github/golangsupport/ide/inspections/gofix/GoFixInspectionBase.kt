package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.ide.rules.builtin.vet.GoVetPsi
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoVersion
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoType

/**
 * Base of the **Go fix** inspections (GoLand's group `Go | Go fix`, the Go team's `modernize` analyzers as the behavioural model).
 *
 * README — conventions every Go fix inspection follows:
 * - **Class**: `GoFixXxxInspection : GoFixInspectionBase()` in this package, one file per inspection, `shortName` = GoLand's id (`GoFixRangeInt`).
 *   The base extends [GoAnalysisInspectionBase], so the inspection is silent while the host serves diagnostics from gopls.
 * - **Version gate**: override [minGoVersion] (`"1.22"`). Files whose language version ([GoFixVersions.languageVersion]: the `//go:build go1.N`
 *   line of the file, else the `go` directive of the module's go.mod) is older are skipped whole; no go.mod means no gate. A shape that
 *   needs a newer version than the inspection's minimum checks [atLeast] itself (maps.Keys needs 1.23 where maps.Copy needs 1.21).
 * - **Detection = plan**: implement [plan]: for an element of the right kind and exact shape, return a [GoFixPlan] (message, fix name, text edits,
 *   imports to add, imports to drop when unused, highlighted range inside the element), else null. The base reports the element with the
 *   plan's message and a [GoFixQuickFix] that calls [plan] again on the reported element when applied (no PSI kept in the fix; the preview
 *   plans on the original file and shows a text diff without the import changes). So [plan] must be a pure function of the PSI, cheap to
 *   reject (an `is` check first, types and resolve only after the syntactic shape matched), and must return null whenever the rewrite is not
 *   certainly equivalent: exact shapes only, types from [GoSemanticService] (never from text), names from [GoScopes] ([GoFixPsi.isUniverse],
 *   [GoFixPsi.qualifier] for the package to write, [GoFixPsi.freshName] for new variables), no comments lost ([GoFixPsi.hasComments]).
 *   Text helpers: [GoFixPsi.replace] / [GoFixPsi.replaceStatement] (`""` removes the statement's line), [GoFixPsi.writes] / [GoFixPsi.mentions].
 * - **Messages**: the gopls `modernize` text where GoLand shows it (`for loop can be modernized using range over int`), else gopls' own text.
 *   Fix names are verbs (`Replace with range over int`); the family is [GoFixQuickFix.FAMILY] for all of them.
 * - **Highlight**: [ProblemHighlightType.GENERIC_ERROR_OR_WARNING], so the level of the XML line decides (WEAK WARNING until the
 *   SYNTAX_UPDATE severity exists).
 * - **Registration**: a line at the end of `META-INF/go-psi-ide-inspections.xml` under `<!-- GoLand parity G5: Go fix -->`:
 *   `<localInspection language="Go" shortName="GoFixXxx" displayName="(GoLand's display name)" groupPath="Go" groupName="Go fix"
 *   enabledByDefault="true" level="WEAK WARNING" implementationClass="io.github.golangsupport.ide.inspections.gofix.GoFixXxxInspection"/>`,
 *   plus `inspectionDescriptions/GoFixXxx.html` (what, before/after, the Go version).
 * - **Tests**: `src/test/.../inspections/gofix/`, a class `GoFixXxxInspectionTest : GoFixTestBase()` overriding `inspection()`; texts are
 *   written with 4-space indents (turned into tabs) and `warn(message, text)` markers: `doTest(before, fixName, after)` checks the
 *   highlighting, applies every fix of that name and compares; `highlight(text)` for near misses that stay quiet; `useGoVersion("1.21")`
 *   for the version gate. `GoFixProbeTest` runs every Go fix inspection over the GoLand probe files and expects only GoLand's finding:
 *   add new inspections to [GoFixInspections.ALL].
 */
abstract class GoFixInspectionBase : GoAnalysisInspectionBase() {

    /** The language version the rewrite needs (`"1.22"` for range over int). */
    protected abstract val minGoVersion: String

    /** The rewrite for [element], or null when [element] is not of the exact shape (called for every element, and again by the quick fix). */
    abstract fun plan(element: PsiElement): GoFixPlan?

    final override fun isApplicable(file: GoFile): Boolean = GoFixVersions.allows(file, minGoVersion)

    /** Whether the file of [element] may use the features of Go [version] (a shape newer than [minGoVersion]). */
    protected fun atLeast(element: PsiElement, version: String): Boolean = (element.containingFile as? GoFile)?.let { GoFixVersions.allows(it, version) } ?: false

    final override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val plan = plan(element) ?: return
        val fix = GoFixQuickFix(plan.fixName, this::plan)
        holder.registerProblem(holder.manager.createProblemDescriptor(element, plan.range, plan.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, holder.isOnTheFly, fix))
    }
}

/**
 * What a Go fix rewrite does: text [edits] of the committed file (non-overlapping), [imports] to add (paths), [dropImports] to remove when
 * nothing uses them afterwards (`sort` after `sort.Slice` → `slices.Sort`); [range] is the highlighted part of the reported element (null: all of it).
 */
class GoFixPlan(
    val message: String,
    val fixName: String,
    val edits: List<GoEditPlan.Edit>,
    val imports: Collection<String> = emptyList(),
    val dropImports: Collection<String> = emptyList(),
    val range: TextRange? = null,
)

/** The quick fix of a Go fix inspection: recomputes the plan from the reported element, edits the document, then fixes the imports. */
class GoFixQuickFix(private val name: String, private val planner: (PsiElement) -> GoFixPlan?) : LocalQuickFix {

    override fun getName(): String = name

    override fun getFamilyName(): String = FAMILY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        apply(descriptor.psiElement ?: return)
    }

    /**
     * The rewrite as a diff of texts: the plan comes from the original file (the preview copy has no document to commit, and types and resolve
     * may not see it; same text, same offsets). Imports are not part of the preview.
     */
    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val element = previewDescriptor.psiElement ?: return IntentionPreviewInfo.EMPTY
        val file = element.containingFile ?: return IntentionPreviewInfo.EMPTY
        val plan = planner(originalOf(element) ?: element) ?: return IntentionPreviewInfo.EMPTY
        val text = StringBuilder(file.text)
        for (edit in plan.edits.sortedByDescending { it.start }) text.replace(edit.start, edit.end, edit.text)
        return IntentionPreviewInfo.CustomDiff(file.fileType, file.name, file.text, text.toString())
    }

    private fun apply(element: PsiElement): Boolean {
        val file = element.containingFile as? GoFile ?: return false
        val plan = planner(element) ?: return false
        val document = GoImportEdits.document(file) ?: return false
        for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        GoImportEdits.commit(file, document)
        // Drop first: a declaration left with one spec then takes the new import as `import "x"`, not as a group of one.
        if (plan.dropImports.isNotEmpty()) {
            val unused = file.imports.filter { it.path in plan.dropImports && !it.isBlank && !it.isDot && !GoFixPsi.isUsed(file, it) }
            if (unused.isNotEmpty()) {
                GoImportEdits.removeSpecs(document, unused)
                GoImportEdits.commit(file, document)
            }
        }
        if (plan.imports.isNotEmpty()) {
            for (path in plan.imports) {
                GoImportInserter.addImport(file, document, path)
                GoImportEdits.commit(file, document)
            }
        }
        return true
    }

    private fun originalOf(element: PsiElement): PsiElement? {
        val original = element.containingFile?.originalFile?.takeIf { it !== element.containingFile } ?: return null
        val range = element.textRange
        return PsiTreeUtil.findElementOfClassAtRange(original, range.startOffset, range.endOffset, element.javaClass)
    }

    companion object {
        const val FAMILY = "Go fix"
    }
}

/** The Go language version a file is written for: what the version gate of [GoFixInspectionBase] compares. */
object GoFixVersions {

    /** Whether [file] may use the features of Go [min] (`"1.21"`); true when the version is unknown (no go.mod, no directive). */
    fun allows(file: GoFile, min: String): Boolean {
        val version = languageVersion(file) ?: return true
        val required = GoVersion.parse(min) ?: return true
        return version >= required.languageVersion
    }

    /** The `//go:build go1.N` version of [file] (a file may pick its own version since Go 1.21), else the module's `go` directive. */
    fun languageVersion(file: GoFile): GoVersion? {
        GoVetPsi.fileGoVersion(file)?.let(GoVersion::parse)?.let { return it.languageVersion }
        val dir = GoPsiUtil.originalFile(file).containingDirectory ?: return moduleVersion(file.project, GoPsiUtil.originalVirtualFile(file).parent)
        return CachedValuesManager.getCachedValue(dir, MODULE_VERSION) {
            val vf = dir.virtualFile
            val version = moduleVersion(dir.project, vf)
            val deps = GoTrackers.getInstance(dir.project).packageDependencies(dir).toMutableList<Any>()
            goModOf(dir.project, vf)?.let { deps += it }
            CachedValueProvider.Result.create(Box(version), *deps.toTypedArray())
        }.value
    }

    /** The `go` directive of the main module containing [dir] (the innermost one in a workspace). */
    private fun moduleVersion(project: Project, dir: VirtualFile?): GoVersion? {
        val module = mainModule(project, dir ?: return null) ?: return null
        return module.goVersion?.let(GoVersion::parse)?.languageVersion
    }

    private fun mainModule(project: Project, dir: VirtualFile): io.github.golangsupport.project.api.GoModule? {
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(dir) ?: return null
        val path = dir.path + "/"
        return graph.mainModules.filter { m -> m.dir?.let { path.startsWith(it.toString().replace(java.io.File.separatorChar, '/') + "/") } == true }
            .maxByOrNull { it.dir.toString().length } ?: graph.mainModules.singleOrNull()
    }

    private fun goModOf(project: Project, dir: VirtualFile): VirtualFile? =
        mainModule(project, dir)?.goModFile?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    private class Box(val value: GoVersion?)

    private val MODULE_VERSION = Key.create<CachedValue<Box>>("gopsi.gofix.moduleVersion")
}

/** PSI, name and type helpers shared by the Go fix inspections. */
internal object GoFixPsi {

    fun unparen(e: PsiElement?): GoExpression? = GoSimplePsi.unparen(e as? GoExpression)

    /** The statement inside a [GoSimpleStatement] wrapper (`x = y`, `i++`), or [statement] itself. */
    fun unwrap(statement: PsiElement?): PsiElement? = (statement as? GoSimpleStatement)?.statement ?: statement

    /** The name of an unqualified reference, else null. */
    fun name(e: PsiElement?): String? = (unparen(e) as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text

    /** The single declaration the (possibly qualified) reference [e] resolves to. */
    fun target(e: PsiElement?): PsiElement? {
        val ref = unparen(e) as? GoReferenceExpression ?: return null
        return GoSemanticService.getInstance(ref.project).resolve(ref).singleOrNull()
    }

    /** [e] is an unqualified name resolving to [def]. */
    fun refersTo(e: PsiElement?, def: PsiElement): Boolean {
        val n = name(e) ?: return false
        return n == (def as? GoNamedElement)?.name && target(e) == def
    }

    /** The same pure expression twice: names resolving to the same declaration, otherwise the same text without spaces. */
    fun same(a: PsiElement?, b: PsiElement?): Boolean {
        val x = unparen(a) ?: return false
        val y = unparen(b) ?: return false
        if (name(x) != null || name(y) != null) return name(x) == name(y) && target(x).let { it != null && it == target(y) }
        return x.javaClass == y.javaClass && GoSimplePsi.norm(x) == GoSimplePsi.norm(y) && isPure(x) && isPure(y)
    }

    /** Evaluating [e] has no side effects (names, selectors, indexing, literals, `len` / `cap`). */
    fun isPure(e: PsiElement?): Boolean = GoSimplePsi.isPure(e)

    fun typeOf(e: GoExpression): GoType = GoSemanticService.getInstance(e.project).typeOf(e)

    /** The integer literal [value] (`0`, `1`), or `-1` for [value] `-1`. */
    fun isInt(e: PsiElement?, value: String): Boolean {
        val x = unparen(e)
        if (value.startsWith("-")) return x is GoUnaryExpr && x.sub != null && isInt(x.expression, value.substring(1))
        return (x as? GoLiteral)?.int?.text == value
    }

    /** [e] is the predeclared [name] (`true`, `len`) and the name is not redeclared where it stands. */
    fun isBuiltin(e: PsiElement?, name: String): Boolean = name(e) == name && isUniverse(e!!, name)

    /** A call of the builtin [name] with one argument: the argument. */
    fun builtinArg(e: PsiElement?, name: String): GoExpression? {
        val call = unparen(e) as? GoCallExpr ?: return null
        if (!isBuiltin(call.expression, name)) return null
        return args(call)?.singleOrNull()
    }

    /** At [place], [name] means the predeclared identifier (`any`, `min`, `true`): not shadowed by a local, package or import name. */
    fun isUniverse(place: PsiElement, name: String): Boolean {
        val target = GoScopes.resolveName(place, name).firstOrNull() ?: return true
        val element = target.element
        return target is GoScopes.Target.Declaration && element is GoNamedElement && GoUniverse.isBuiltinDeclaration(element)
    }

    /**
     * How to write the package [path] at [place] (`slices`): the name of its import, or its default name when it is not imported (the fix
     * then adds the import). Null when the name means something else there (a variable `slices`, another package imported as `slices`).
     */
    fun qualifier(place: PsiElement, path: String): String? {
        val file = place.containingFile as? GoFile ?: return null
        file.imports.firstOrNull { it.path == path && !it.isBlank && !it.isDot }?.let { return GoScopes.importName(it) }
        val default = path.substringAfterLast('/')
        return default.takeIf { GoScopes.resolveName(place, it).isEmpty() }
    }

    /** `pkg.Name` of [call] when the callee resolves to a function of package [path] with one of [names]; the name, else null. */
    fun packageFunction(call: GoCallExpr, path: String, names: Set<String>): String? {
        val ref = unparen(call.expression) as? GoReferenceExpression ?: return null
        val name = ref.identifier.text
        if (name !in names || ref.expression !is GoReferenceExpression) return null
        val t = target(ref) as? GoFunctionOrMethodDeclaration ?: return null
        return name.takeIf { GoAnalysisPsi.packagePath(t) == path }
    }

    /** The argument expressions of [call] (null when one is a type or the call has `...`). */
    fun args(call: GoCallExpr): List<GoExpression>? {
        val list = call.argumentList
        if (GoPsiUtil.hasChildToken(list, GoTypes.ELLIPSIS)) return null
        val all = GoPsiUtil.children(list, PsiElement::class.java).filter { it is GoExpression || it is io.github.golangsupport.lang.psi.GoType }
        return all.map { it as? GoExpression ?: return null }
    }

    /** Every reference in [scope] (itself included) to [def]: a text check first, then resolve. */
    fun references(scope: PsiElement, def: GoNamedElement): List<GoReferenceExpression> {
        val name = def.name ?: return emptyList()
        if (!scope.text.contains(name)) return emptyList()
        return (PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java) + listOfNotNull(scope as? GoReferenceExpression))
            .filter { it.identifier.text == name && target(it) == def }
    }

    fun mentions(scope: PsiElement, def: GoNamedElement): Boolean = references(scope, def).isNotEmpty()

    /** Whether [scope] has the identifier token [name] anywhere (a declaration, a use, a field). */
    fun mentionsName(scope: PsiElement, name: String): Boolean = GoSimplePsi.mentions(scope, name)

    /** Whether [scope] assigns [def], increments it, takes its address or uses it as a range `=` target. */
    fun writes(scope: PsiElement, def: GoNamedElement): Boolean = references(scope, def).any(::isWrite)

    /** A reference used as an assignment / `++` / range `=` target, or the operand of `&`. */
    fun isWrite(ref: GoReferenceExpression): Boolean {
        val parent = ref.parent
        if (parent is GoUnaryExpr && parent.and != null) return true
        if (parent is GoLeftHandExprList) return parent.parent is GoAssignmentStatement || parent.parent is GoIncDecStatement || parent.parent is GoRangeClause
        return false
    }

    /** A local variable or parameter (what a write in a loop body can change; package-level state can change behind any call). */
    fun isLocal(def: PsiElement?): Boolean = (def is GoVarDefinition || def is GoParamDefinition) && GoPsiUtil.functionOwner(def) != null

    /** A constant (declared anywhere). */
    fun isConst(def: PsiElement?): Boolean = def is GoConstDefinition

    /** A comment anywhere in [element]. */
    fun hasComments(element: PsiElement): Boolean = PsiTreeUtil.findChildOfType(element, PsiComment::class.java) != null

    /** The statement after [statement] in its block or clause. */
    fun next(statement: PsiElement): GoStatement? {
        val list = statement.parent?.takeIf { GoEditText.isStatementList(it) } ?: return null
        val all = GoEditText.statements(list)
        return all.getOrNull(all.indexOf(statement) + 1)
    }

    /** The statement before [statement] in its block or clause. */
    fun previous(statement: PsiElement): GoStatement? {
        val list = statement.parent?.takeIf { GoEditText.isStatementList(it) } ?: return null
        val all = GoEditText.statements(list)
        val i = all.indexOf(statement)
        return if (i > 0) all[i - 1] else null
    }

    /** The statements after [statement] in its list. */
    fun following(statement: PsiElement): List<GoStatement> {
        val list = statement.parent?.takeIf { GoEditText.isStatementList(it) } ?: return emptyList()
        val all = GoEditText.statements(list)
        return all.drop(all.indexOf(statement) + 1)
    }

    /** The statements of [block]. */
    fun statements(block: GoBlock?): List<GoStatement> = block?.let(GoEditText::statements).orEmpty()

    /** [statement] stands in a block or clause statement list. */
    fun inList(statement: PsiElement): Boolean = GoEditText.isStatementList(statement.parent)

    /** The keyword (first token) of [statement], as a range inside it. */
    fun keywordRange(statement: PsiElement): TextRange = TextRange(0, statement.firstChild?.textLength ?: statement.textLength)

    /** [inner]'s range relative to [outer]. */
    fun rangeIn(outer: PsiElement, inner: PsiElement): TextRange = inner.textRange.shiftLeft(outer.textRange.startOffset)

    /** Replaces [element] by [text]. */
    fun replace(element: PsiElement, text: String): GoEditPlan.Edit = GoEditPlan.Edit(element.textRange.startOffset, element.textRange.endOffset, text)

    /** Replaces the statement [statement] by [text] ("" removes its line). */
    fun replaceStatement(statement: PsiElement, text: String): GoEditPlan.Edit = GoEditText.replaceStatement(statement, text)

    /** The leading spaces and tabs of the line [element] starts on. */
    fun indentOf(element: PsiElement): String = GoEditText.indentOf(element.containingFile.node.chars, element.textRange.startOffset)

    /** A basic type: integer (not a rune-only untyped constant), string, and, when [floats], floating point. */
    fun isOrderedBasic(type: GoType, floats: Boolean): Boolean {
        val b = type.underlying() as? GoBasicType ?: return false
        return b.kind.isInteger || b.kind.isString || (floats && b.kind.isFloat)
    }

    /** Whether the file still writes `name.` for the import [spec] outside the import declarations. */
    fun isUsed(file: GoFile, spec: GoImportSpec): Boolean {
        val name = GoScopes.importName(spec)
        if (name.isEmpty()) return true
        return PsiTreeUtil.collectElements(file) { e ->
            e.node.elementType == GoTypes.IDENTIFIER && e.text == name && PsiTreeUtil.getParentOfType(e, GoImportSpec::class.java) == null &&
                PsiTreeUtil.nextVisibleLeaf(e)?.node?.elementType == GoTypes.PERIOD
        }.isNotEmpty()
    }

    /** The first of [candidates] that [scope] does not mention as an identifier and that is not visible at [place]. */
    fun freshName(scope: PsiElement, place: PsiElement, vararg candidates: String): String? =
        candidates.firstOrNull { !mentionsName(scope, it) && GoScopes.resolveName(place, it).isEmpty() }

    /** The init statement, condition and post statement of a three-clause `for`. */
    fun forParts(clause: GoForClause): GoSimplePsi.ForParts? = GoSimplePsi.forParts(clause)
}
