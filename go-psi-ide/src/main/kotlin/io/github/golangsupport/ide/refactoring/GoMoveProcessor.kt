package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import org.jetbrains.annotations.TestOnly

/**
 * Where Move puts the declarations: [directory] (a system-independent absolute path; created when missing, its package is then
 * named after it) and [fileName] in it (created when missing). [moveMethods]: within one package, the methods of a moved type go
 * with it (always so to another package).
 */
class GoMoveOptions(val directory: String, val fileName: String, val moveMethods: Boolean = true)

/** The target of a move, read before anything changes. [dir] and [file] are null when they are still to be created. */
class GoMoveTarget(val path: String, val dir: VirtualFile?, val file: GoFile?, val fileName: String, val packageName: String, val importPath: String?, val samePackage: Boolean)

internal data class GoMoveEdit(val range: TextRange, val text: String)

internal data class GoMoveImport(val path: String, val alias: String?)

/** Everything a move changes, computed on the unchanged PSI. */
internal class GoMovePlan(
    val units: List<GoMoveUnit>,
    val target: GoMoveTarget,
    val texts: List<String>,
    val edits: Map<GoFile, List<GoMoveEdit>>,
    val imports: Map<GoFile, Set<GoMoveImport>>,
    val targetImports: Set<GoMoveImport>,
    val cleanup: Map<GoFile, Set<String>>,
    val conflicts: MultiMap<PsiElement, String>,
    val usages: List<PsiElement>,
)

/**
 * Move (F6) of package-level declarations ([GoMoveDeclarations] decides what goes). Within one package the text moves to another
 * file with the imports it needs; the imports it leaves unused in the source go. To another package of the project the references
 * follow: `Name` in the source package becomes `pkg.Name` (with the import), `old.Name` elsewhere becomes `pkg.Name`, `old.Name`
 * inside the target package loses its qualifier, and the moved text qualifies what it uses from the source package. What cannot
 * be rewritten is a conflict: unexported names used across the new package boundary, import cycles, names the target package
 * declares already, dot imports.
 */
class GoMoveProcessor(project: Project, private val units: List<GoMoveUnit>, private val target: GoMoveTarget) : BaseRefactoringProcessor(project) {

    private var plan: GoMovePlan? = null

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor = object : UsageViewDescriptor {
        override fun getElements(): Array<PsiElement> = units.map { it.element }.toTypedArray()
        override fun getProcessedElementsHeader(): String = "Declarations to move to ${target.fileName}"
        override fun getCodeReferencesText(usagesCount: Int, filesCount: Int): String = "References to update ($usagesCount in $filesCount files)"
    }

    override fun findUsages(): Array<UsageInfo> {
        val p = GoMovePlanner(myProject, units, target).plan()
        plan = p
        return p.usages.filter { it.isValid }.map(::UsageInfo).toTypedArray()
    }

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean = showConflicts(plan?.conflicts ?: MultiMap(), refUsages.get())

    override fun getElementsToWrite(descriptor: UsageViewDescriptor): Collection<PsiElement> =
        super.getElementsToWrite(descriptor) + units.map { it.file } + listOfNotNull(target.file)

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val p = plan?.takeIf { pl -> pl.units.all { it.element.isValid } } ?: GoMovePlanner(myProject, units, target).plan()
        apply(myProject, p)
    }

    override fun getCommandName(): String = TITLE

    companion object {
        const val TITLE = "Move"

        /** The units and the target of moving [elements] with [options]; [GoMoveRefusal] when that cannot be done. */
        fun create(project: Project, elements: Collection<PsiElement>, options: GoMoveOptions): GoMoveProcessor {
            val first = elements.firstOrNull()?.let(GoMoveDeclarations::movable) ?: throw GoMoveRefusal("Only package-level declarations can be moved")
            val source = first.containingFile as GoFile
            val target = target(project, source, options)
            val units = GoMoveDeclarations.units(elements, options.moveMethods, !target.samePackage)
            if (target.file != null && units.any { it.file == target.file }) throw GoMoveRefusal("${target.fileName} already contains ${GoMoveDeclarations.describe(units).first()}")
            if (target.file != null && target.samePackage.not() && target.file.containingDirectory?.virtualFile == source.containingDirectory?.virtualFile) {
                throw GoMoveRefusal("${target.fileName} belongs to package ${target.packageName}, not ${source.packageName}")
            }
            var probe: VirtualFile? = target.dir ?: LocalFileSystem.getInstance().findFileByPath(target.path.substringBeforeLast('/'))
            var up = target.path.substringBeforeLast('/')
            while (probe == null && up.contains('/')) {
                up = up.substringBeforeLast('/')
                probe = LocalFileSystem.getInstance().findFileByPath(up)
            }
            if (probe == null || !ProjectFileIndex.getInstance(project).isInContent(probe) || ProjectFileIndex.getInstance(project).isInLibrary(probe)) {
                throw GoMoveRefusal("${target.path} is not in the project")
            }
            if (!target.samePackage && target.importPath == null) throw GoMoveRefusal("${target.path} is not inside a Go module")
            return GoMoveProcessor(project, units, target)
        }

        /** Runs the move without the dialog (conflicts throw in tests, like every refactoring processor). */
        @TestOnly
        fun moveForTests(project: Project, elements: Collection<PsiElement>, options: GoMoveOptions) = create(project, elements, options).run()

        fun target(project: Project, source: GoFile, options: GoMoveOptions): GoMoveTarget {
            val path = options.directory.replace('\\', '/').trimEnd('/')
            val fileName = options.fileName.trim().let { if (it.endsWith(".go")) it else "$it.go" }
            if (fileName == ".go" || fileName.contains('/')) throw GoMoveRefusal("Enter a file name")
            val dir = LocalFileSystem.getInstance().findFileByPath(path)?.takeIf { it.isDirectory }
            val sourceDir = source.containingDirectory?.virtualFile
            val psiManager = PsiManager.getInstance(project)
            val file = dir?.findChild(fileName)?.let { psiManager.findFile(it) as? GoFile }
            val sourceName = source.packageName ?: throw GoMoveRefusal("The source file has no package clause")
            val packageName = file?.packageName
                ?: if (dir == sourceDir) sourceName
                else dir?.children?.filter { it.extension == "go" }?.mapNotNull { psiManager.findFile(it) as? GoFile }
                    ?.firstOrNull { !it.isTestFile && it.packageName?.endsWith("_test") == false }?.packageName
                ?: packageNameOf(path.substringAfterLast('/'))
            val resolver = GoPackageResolver.getInstance(project)
            val importPath = if (dir != null) resolver.importPathOf(dir) else {
                var ancestor = path.substringBeforeLast('/', "")
                var rest = path.substringAfterLast('/')
                var existing = LocalFileSystem.getInstance().findFileByPath(ancestor)
                while (existing == null && ancestor.contains('/')) {
                    rest = ancestor.substringAfterLast('/') + "/" + rest
                    ancestor = ancestor.substringBeforeLast('/')
                    existing = LocalFileSystem.getInstance().findFileByPath(ancestor)
                }
                existing?.let(resolver::importPathOf)?.let { "$it/$rest" }
            }
            return GoMoveTarget(path, dir, file, fileName, packageName, importPath, dir == sourceDir && packageName == sourceName)
        }

        /** A package name for a new directory: its name with the characters an identifier cannot hold replaced. */
        fun packageNameOf(dirName: String): String {
            val name = dirName.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").lowercase()
            return if (name.isEmpty() || name[0].isDigit()) "_$name" else name
        }

        /** Applies [plan]: creates the target, edits every file, adds the imports and drops the ones the move left unused. */
        internal fun apply(project: Project, plan: GoMovePlan) {
            val target = plan.target
            val psiManager = PsiManager.getInstance(project)
            val dir = target.dir ?: VfsUtil.createDirectoryIfMissing(target.path) ?: error("Cannot create ${target.path}")
            val targetFile = target.file ?: run {
                val psiDir = psiManager.findDirectory(dir) ?: error("No directory ${target.path}")
                val created = psiDir.createFile(target.fileName) as GoFile
                val document = GoImportEdits.document(created)!!
                document.setText("package ${target.packageName}\n")
                GoImportEdits.commit(created, document)
                created
            }
            val edits = LinkedHashMap<GoFile, MutableList<GoMoveEdit>>()
            for ((f, list) in plan.edits) edits.getOrPut(f) { ArrayList() } += list
            val targetText = PsiDocumentManager.getInstance(project).getDocument(targetFile)?.charsSequence ?: targetFile.text
            val append = (if (targetText.endsWith("\n") || targetText.isEmpty()) "" else "\n") + "\n" + plan.texts.joinToString("\n\n") + "\n"
            edits.getOrPut(targetFile) { ArrayList() } += GoMoveEdit(TextRange(targetText.length, targetText.length), append)
            for ((f, list) in edits) {
                val document = GoImportEdits.document(f) ?: continue
                for (e in list.sortedWith(compareByDescending<GoMoveEdit> { it.range.startOffset }.thenByDescending { it.range.endOffset })) {
                    document.replaceString(e.range.startOffset, e.range.endOffset, e.text)
                }
                GoImportEdits.commit(f, document)
            }
            val imports = LinkedHashMap<GoFile, MutableSet<GoMoveImport>>()
            for ((f, needs) in plan.imports) imports.getOrPut(f) { LinkedHashSet() } += needs
            imports.getOrPut(targetFile) { LinkedHashSet() } += plan.targetImports
            for ((f, needs) in imports) {
                val document = GoImportEdits.document(f) ?: continue
                for (need in needs) {
                    GoImportInserter.addImport(f, document, need.path, need.alias)
                    GoImportEdits.commit(f, document)
                }
            }
            for ((f, paths) in plan.cleanup) {
                if (!f.isValid) continue
                val unused = f.imports.filter { it.path in paths && !it.isDot && !it.isBlank && !isUsed(f, it) }
                if (unused.isEmpty()) continue
                val document = GoImportEdits.document(f) ?: continue
                GoImportEdits.removeSpecs(document, unused)
                GoImportEdits.commit(f, document)
            }
        }

        /** True when a qualifier of [file] still selects through [spec]; a qualifier that resolves to nothing counts as a use. */
        private fun isUsed(file: GoFile, spec: GoImportSpec): Boolean {
            val name = GoScopes.importName(spec)
            return PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java).any { r ->
                r.expression == null && r.identifier.text == name && GoMovePlanner.isQualifier(r) && r.reference?.resolve().let { it == null || it == spec }
            }
        }
    }
}

/** Computes a [GoMovePlan]: the text for the target and every reference rewrite, import and conflict. */
internal class GoMovePlanner(private val project: Project, private val units: List<GoMoveUnit>, private val target: GoMoveTarget) {

    private val source: GoFile = units.first().file
    private val sourceDir: VirtualFile? = source.containingDirectory?.virtualFile
    private val sourceName: String = source.packageName.orEmpty()
    private val resolver = GoPackageResolver.getInstance(project)
    private val sourcePath: String? = sourceDir?.let(resolver::importPathOf)
    private val model = GoPackageModel.getInstance(project)

    private val conflicts = MultiMap<PsiElement, String>()
    private val edits = LinkedHashMap<GoFile, MutableList<GoMoveEdit>>()
    private val imports = LinkedHashMap<GoFile, MutableSet<GoMoveImport>>()
    private val targetImports = LinkedHashSet<GoMoveImport>()
    private val cleanup = LinkedHashMap<GoFile, MutableSet<String>>()
    private val usages = ArrayList<PsiElement>()
    /** Directories of the packages that will import the target package. */
    private val newImporters = LinkedHashMap<VirtualFile, PsiElement>()
    /** Where the moved text uses the source package (the target will import it), or null. */
    private var usesSource: PsiElement? = null

    fun plan(): GoMovePlan {
        val unitEdits = units.associateWith { ArrayList<GoMoveEdit>() }
        for (u in units) rewriteMovedText(u, unitEdits.getValue(u))
        if (!target.samePackage) {
            for (u in units) for (n in u.names) {
                ReferencesSearch.search(n, GlobalSearchScope.projectScope(project), false).forEach { ref -> usage(ref.element, n); true }
            }
            unexportedMembers()
            nameClashes()
            cycles()
        }
        for ((file, list) in units.groupBy { it.file }) {
            val text = file.viewProvider.contents
            val ranges = list.map { GoSafeDeleteProcessor.lines(text, it.range) }.sortedBy { it.startOffset }
            val merged = ArrayList<TextRange>()
            for (r in ranges) if (merged.isNotEmpty() && merged.last().endOffset >= r.startOffset) merged[merged.size - 1] = merged.last().union(r) else merged += r
            for (r in merged) edits.getOrPut(file) { ArrayList() } += GoMoveEdit(r, "")
        }
        val texts = units.map { u ->
            val text = u.file.viewProvider.contents
            val b = StringBuilder(text.subSequence(u.range.startOffset, u.range.endOffset))
            for (e in unitEdits.getValue(u).sortedByDescending { it.range.startOffset }) {
                b.replace(e.range.startOffset - u.range.startOffset, e.range.endOffset - u.range.startOffset, e.text)
            }
            u.targetText(b.toString())
        }
        return GoMovePlan(units, target, texts, edits, imports, targetImports, cleanup, conflicts, usages)
    }

    private fun moved(e: PsiElement): Boolean = units.any { it.contains(e) }

    private fun inSource(e: PsiElement): Boolean {
        val f = e.containingFile as? GoFile ?: return false
        return f.virtualFile?.parent == sourceDir && f.packageName == sourceName
    }

    private fun inTarget(f: GoFile): Boolean = target.dir != null && f.virtualFile?.parent == target.dir && f.packageName == target.packageName

    /** The local name [file] uses for the target package: an existing import of it, or the package name. */
    private fun targetQualifier(file: GoFile, at: PsiElement): String {
        val path = target.importPath ?: return target.packageName
        file.imports.firstOrNull { it.path == path && !it.isDot && !it.isBlank }?.let { return GoScopes.importName(it) }
        val name = target.packageName
        val clash = file.imports.firstOrNull { it.path != path && !it.isBlank && !it.isDot && GoScopes.importName(it) == name }
        if (clash != null || model.scopeOf(file).lookup(name).isNotEmpty()) add(at, "'$name' already means something else in ${file.name}; the reference cannot be qualified with it")
        imports.getOrPut(file) { LinkedHashSet() } += GoMoveImport(path, null)
        return name
    }

    /** The qualifier the moved text uses for the source package: an import of it the target file has, or the package name. */
    private val sourceQualifier: String by lazy {
        target.file?.imports?.firstOrNull { it.path == sourcePath && !it.isDot && !it.isBlank }?.let { GoScopes.importName(it) } ?: sourceName
    }

    /** The edits inside a moved unit: imports it needs follow it; across packages, source names get qualified and target qualifiers go. */
    private fun rewriteMovedText(u: GoMoveUnit, out: MutableList<GoMoveEdit>) {
        val cross = !target.samePackage
        for (r in PsiTreeUtil.findChildrenOfType(u.element, GoReferenceExpression::class.java)) {
            val resolved = r.reference?.resolve() ?: continue
            if (resolved is GoImportSpec) {
                if (!isQualifier(r)) continue
                cleanup.getOrPut(u.file) { LinkedHashSet() } += resolved.path
                if (cross && resolved.path == target.importPath) {
                    out += GoMoveEdit(TextRange(r.textRange.startOffset, selected(r)!!.textRange.startOffset), "")
                } else {
                    targetImports += GoMoveImport(resolved.path, resolved.alias)
                }
                continue
            }
            if (r.expression == null) {
                if (cross) qualifySource(r, resolved, out)
                dotImported(u.file, resolved)
            } else if (cross && resolved is GoNamedElement && (resolved is GoFieldDefinition || resolved is GoMethodDeclaration || resolved is GoMethodSpec) &&
                !resolved.isPublic() && inSource(resolved) && !moved(resolved)) {
                add(r, "${resolved.name} is unexported and used from package ${target.packageName}")
            }
        }
        for (tr in PsiTreeUtil.findChildrenOfType(u.element, GoTypeReferenceExpression::class.java)) {
            if (tr.referenceExpression != null) continue
            val resolved = tr.reference?.resolve() ?: continue
            if (cross) qualifySource(tr, resolved, out)
            dotImported(u.file, resolved)
        }
    }

    /** An unqualified [ref] in moved text to a package-level name of the source package that stays: `src.Name`. */
    private fun qualifySource(ref: PsiElement, resolved: PsiElement, out: MutableList<GoMoveEdit>) {
        if (!GoMoveDeclarations.isTopLevel(resolved) || !inSource(resolved) || moved(resolved)) return
        val name = (resolved as GoNamedElement).name ?: return
        if (!resolved.isPublic()) add(ref, "$name is unexported and used from package ${target.packageName}")
        if (sourceName == "main") add(ref, "$name stays in package main, which cannot be imported")
        if (sourcePath == null) add(ref, "$name stays in package $sourceName, which has no import path")
        usesSource = usesSource ?: ref
        out += GoMoveEdit(TextRange(ref.textRange.startOffset, ref.textRange.startOffset), "$sourceQualifier.")
        sourcePath?.let { path -> targetImports += GoMoveImport(path, if (sourceQualifier == sourceName) null else sourceQualifier) }
    }

    /** A name of another package the moved text reaches through a dot import: the target needs the dot import too. */
    private fun dotImported(file: GoFile, resolved: PsiElement) {
        if (!GoMoveDeclarations.isTopLevel(resolved)) return
        val dir = resolved.containingFile?.virtualFile?.parent ?: return
        if (dir == sourceDir || dir == target.dir) return
        val spec = file.imports.firstOrNull { it.isDot && model.resolveImport(it.path, file)?.directory == dir } ?: return
        targetImports += GoMoveImport(spec.path, ".")
    }

    /** A reference [e] to the moved [name] outside the moved text. */
    private fun usage(e: PsiElement, name: GoNamedElement) {
        if (moved(e)) return
        val file = e.containingFile as? GoFile ?: return
        val site = (e as? GoReferenceExpression ?: e as? GoTypeReferenceExpression) ?: (e.parent as? GoReferenceExpression ?: e.parent as? GoTypeReferenceExpression) ?: return
        val qualifier = (site as? GoReferenceExpression)?.expression ?: (site as? GoTypeReferenceExpression)?.referenceExpression
        val identifier = (site as? GoReferenceExpression)?.identifier ?: (site as GoTypeReferenceExpression).identifier
        usages += site
        val list = edits.getOrPut(file) { ArrayList() }
        when {
            inSource(file) -> {
                if (!name.isPublic()) add(site, "${name.name} is unexported and used from package $sourceName")
                newImporters.putIfAbsent(file.virtualFile.parent, site)
                list += GoMoveEdit(TextRange(site.textRange.startOffset, site.textRange.startOffset), targetQualifier(file, site) + ".")
            }
            inTarget(file) -> if (qualifier != null) {
                list += GoMoveEdit(TextRange(qualifier.textRange.startOffset, identifier.textRange.startOffset), "")
                sourcePath?.let { cleanup.getOrPut(file) { LinkedHashSet() } += it }
            }
            qualifier == null -> add(site, "${name.name} is used through a dot import in ${file.name}; the reference is not updated")
            else -> {
                newImporters.putIfAbsent(file.virtualFile.parent, site)
                list += GoMoveEdit(qualifier.textRange, targetQualifier(file, site))
                sourcePath?.let { cleanup.getOrPut(file) { LinkedHashSet() } += it }
            }
        }
    }

    /** Unexported fields and methods of moved types that the source package uses: they become invisible to it. */
    private fun unexportedMembers() {
        val scope = GlobalSearchScope.projectScope(project)
        for (u in units) {
            val members = u.types.flatMap { PsiTreeUtil.findChildrenOfType(it, GoFieldDefinition::class.java) } + listOfNotNull(u.element as? GoMethodDeclaration)
            for (m in members) {
                if (m.isPublic()) continue
                val owner = (m as? GoMethodDeclaration)?.receiverTypeName ?: PsiTreeUtil.getParentOfType(m, io.github.golangsupport.lang.psi.GoTypeSpec::class.java)?.name
                ReferencesSearch.search(m, scope, false).forEach { ref ->
                    val e = ref.element
                    if (!moved(e) && inSource(e)) add(e, "$owner.${m.name} is unexported and used from package $sourceName")
                    true
                }
            }
        }
    }

    private fun nameClashes() {
        val dir = target.dir ?: return
        val file = target.file ?: dir.children.filter { it.extension == "go" }.mapNotNull { PsiManager.getInstance(project).findFile(it) as? GoFile }
            .firstOrNull { it.packageName == target.packageName && !it.isTestFile } ?: return
        val scope = model.scopeOf(file)
        for (u in units) for (n in u.names) {
            val name = n.name ?: continue
            if (scope.lookup(name).any { !moved(it) }) add(n, "'$name' is already declared in package ${target.packageName}")
        }
    }

    /** New imports (target -> source, importers -> target) that close a cycle with the imports the packages have already. */
    private fun cycles() {
        val targetPath = target.importPath
        val sourceDirectory = sourceDir ?: return
        val uses = usesSource
        if (uses != null) {
            if (sourceDirectory in newImporters || target.dir != null && imports(sourceDirectory, target.dir)) {
                add(uses, "Import cycle: package ${target.packageName} would import $sourceName, which imports ${target.packageName}")
            }
        }
        if (targetPath == null) return
        for ((dir, at) in newImporters) {
            val name = if (dir == sourceDirectory) sourceName else dir.name
            when {
                target.packageName == "main" -> add(at, "Package main cannot be imported by $name")
                target.dir != null && imports(target.dir, dir) -> add(at, "Import cycle: package $name would import ${target.packageName}, which imports $name")
                uses != null && dir != sourceDirectory && imports(sourceDirectory, dir) ->
                    add(at, "Import cycle: package $name would import ${target.packageName}, which would import $sourceName, which imports $name")
            }
        }
    }

    /** The target package still imports the source after the move: the moved text uses it, or the target uses names that stay. */
    private val targetKeepsSource: Boolean by lazy {
        if (usesSource != null) return@lazy true
        val dir = target.dir ?: return@lazy false
        val movedNames = units.flatMap { u -> u.names.mapNotNull { it.name } }.toSet()
        dir.children.filter { it.extension == "go" }.mapNotNull { PsiManager.getInstance(project).findFile(it) as? GoFile }.filter(::inTarget).any { f ->
            f.imports.filter { it.path == sourcePath && !it.isBlank }.any { spec ->
                val name = GoScopes.importName(spec)
                spec.isDot || PsiTreeUtil.findChildrenOfType(f, GoReferenceExpression::class.java).any { r ->
                    r.expression == null && r.identifier.text == name && selected(r)?.text?.let { it !in movedNames } == true && r.reference?.resolve() == spec
                }
            }
        }
    }

    /** True when the package in [from] imports the one in [to], directly or through other project packages (by [GoFileImportsIndex]). */
    private fun imports(from: VirtualFile, to: VirtualFile): Boolean {
        val start = resolver.importPathOf(to) ?: return false
        val scope = GlobalSearchScope.projectScope(project)
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!seen.add(path)) continue
            for (vf in GoFileImportsIndex.filesImporting(path, project, scope)) {
                // an external test package (`x_test`) is not part of the package it sits next to
                if (vf.name.endsWith("_test.go") && (PsiManager.getInstance(project).findFile(vf) as? GoFile)?.packageName?.endsWith("_test") == true) continue
                val dir = vf.parent ?: continue
                // the target's import of the source goes away when the moved names were all it used it for
                if (path == sourcePath && dir == target.dir && !targetKeepsSource) continue
                if (dir == from) return true
                resolver.importPathOf(dir)?.let(queue::addLast)
            }
        }
        return false
    }

    private fun add(at: PsiElement, message: String) {
        if (message !in conflicts[at]) conflicts.putValue(at, message)
    }

    companion object {
        /** The name selected through [q] (`F` of `pkg.F`, `T` of the type `pkg.T`), or null when [q] is not a package qualifier. */
        fun selected(q: GoReferenceExpression): PsiElement? = when (val p = q.parent) {
            is GoReferenceExpression -> if (p.expression === q) p.identifier else null
            is GoTypeReferenceExpression -> if (p.referenceExpression === q) p.identifier else null
            else -> null
        }

        fun isQualifier(q: GoReferenceExpression): Boolean = selected(q) != null
    }
}
