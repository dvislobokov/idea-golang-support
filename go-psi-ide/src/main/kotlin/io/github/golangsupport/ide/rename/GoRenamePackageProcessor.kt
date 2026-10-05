package io.github.golangsupport.ide.rename

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.GoRenameChoice
import com.intellij.openapi.project.Project
import com.intellij.refactoring.rename.RenameDialog
import java.awt.GridBagConstraints
import javax.swing.JCheckBox
import javax.swing.JPanel
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes

/**
 * Rename Package: a package clause, an unaliased import of a project package (substituted by the
 * package clause) or a package directory in the Project view.
 *
 * - Package clause `old` -> `new`: every `.go` file of the directory with `package old` gets `package new`,
 *   external test files (`package old_test`) get `package new_test`; qualifiers `old.X` in files importing the
 *   package without an alias become `new.X` (aliased imports keep their alias). When the directory is named
 *   after the package (and is not the module root), it is renamed too, which updates the import paths.
 * - Directory `old` -> `new`: import paths `…/old` and `…/old/sub…` in the project are rewritten (inside the
 *   quotes, so the quote style stays). When the package in it is named after the directory, is not `main` and
 *   `new` is a Go identifier, the package is renamed as above; a directory name with `-` or `.` leaves the
 *   package clause alone.
 * - Conflicts (shown in the platform's conflicts dialog): the new package name equals another import's
 *   name or a package-level name in an importing file; a sibling directory with the new name exists.
 *
 * Packages outside the project content (GOROOT, module cache) are vetoed ([GoLibraryPackageRenameVeto]).
 * The module root directory is left to the platform: its import path comes from go.mod, not from its name.
 * Stands down while [GoIdeFeature.RENAME] is off.
 */
class GoRenamePackageProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)) return false
        return when (element) {
            is GoPackageClause -> inProjectContent(element.containingFile?.containingDirectory)
            is PsiDirectory -> isPackageDirectory(element)
            is GoImportSpec -> importedClause(element) != null
            else -> false
        }
    }

    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? = when (element) {
        is GoImportSpec -> importedClause(element) ?: element
        is GoPackageClause -> {
            // `package x_test`: the package under test is what gets renamed (its clause renames this one too).
            val name = element.name.orEmpty()
            val base = name.removeSuffix("_test")
            if (base != name) element.containingFile?.containingDirectory?.let { primaryClause(it, base) } ?: element else element
        }
        else -> element
    }

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        when (element) {
            is GoPackageClause -> {
                val dir = element.containingFile?.containingDirectory ?: return
                val old = element.name ?: return
                addClauses(dir, old, newName, allRenames)
                if (dir.name == old && dir.name != newName && isPackageDirectory(dir) && linked(GoIdeOptions.getInstance().renamePackageDirectory)) allRenames[dir] = newName
            }
            is PsiDirectory -> {
                val old = element.name
                if (old != "main" && GoNamesValidator.isValidIdentifier(newName) && primaryClause(element, old) != null &&
                    linked(GoIdeOptions.getInstance().renameDirectoryPackage)) {
                    addClauses(element, old, newName, allRenames)
                }
            }
        }
    }

    private fun linked(choice: GoRenameChoice): Boolean = when (choice) {
        GoRenameChoice.ALWAYS -> true
        GoRenameChoice.NEVER -> false
        GoRenameChoice.ASK -> askedLinked
    }

    /**
     * With [GoRenameChoice.ASK] the rename dialog of a package clause or a package directory has a check box for the other half
     * ("Rename directory" / "Rename package"); its state is what [prepareRenaming] reads, and it stays for the renames without a dialog.
     */
    override fun createRenameDialog(project: Project, element: PsiElement, nameSuggestionContext: PsiElement?, editor: Editor?): RenameDialog {
        val options = GoIdeOptions.getInstance()
        val label = when {
            element is GoPackageClause && options.renamePackageDirectory == GoRenameChoice.ASK -> "Rename directory"
            element is PsiDirectory && options.renameDirectoryPackage == GoRenameChoice.ASK -> "Rename package"
            else -> return super.createRenameDialog(project, element, nameSuggestionContext, editor)
        }
        return object : RenameDialog(project, element, nameSuggestionContext, editor) {
            private val linkedBox = JCheckBox(label, askedLinked)

            override fun createCheckboxes(panel: JPanel, gbConstraints: GridBagConstraints) {
                super.createCheckboxes(panel, gbConstraints)
                gbConstraints.gridx = 0
                gbConstraints.gridy = GridBagConstraints.RELATIVE
                gbConstraints.gridwidth = GridBagConstraints.REMAINDER
                gbConstraints.fill = GridBagConstraints.HORIZONTAL
                panel.add(linkedBox, gbConstraints)
            }

            override fun doAction() {
                askedLinked = linkedBox.isSelected
                super.doAction()
            }
        }
    }

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> =
        when (element) {
            is GoPackageClause -> qualifierReferences(element)
            is PsiDirectory -> {
                // The platform's own references (file links elsewhere) stay; Go import paths are rewritten by renameElement.
                val others = super.findReferences(element, searchScope, searchInCommentsAndStrings).filter { it.element !is GoImportSpec }
                others + importersOf(element, withSubpackages = true).mapNotNull { it.reference }
            }
            else -> super.findReferences(element, searchScope, searchInCommentsAndStrings)
        }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        if (element !is PsiDirectory) return super.renameElement(element, newName, usages, listener)
        val oldPath = importPathOf(element)
        val newPath = oldPath?.let { it.substringBeforeLast('/') + "/" + newName }
        for (usage in usages) {
            val spec = usage.element as? GoImportSpec
            if (spec == null) {
                usage.reference?.handleElementRename(newName)
                continue
            }
            if (oldPath == null || newPath == null) continue
            val path = spec.path
            val updated = when {
                path == oldPath -> newPath
                path.startsWith("$oldPath/") -> newPath + path.removePrefix(oldPath)
                else -> continue
            }
            val manipulator = ElementManipulators.getManipulator(spec) ?: continue
            manipulator.handleContentChange(spec, manipulator.getRangeInElement(spec), updated)
        }
        element.setName(newName)
        listener?.elementRenamed(element)
    }

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>, allRenames: Map<PsiElement, String>) {
        // Called for the primary element only on some paths: check every package element of this rename.
        val all = allRenames + (element to newName)
        for ((e, name) in all) {
            when (e) {
                is GoPackageClause -> if (isPrimary(e)) packageNameConflicts(e, name, conflicts)
                is PsiDirectory -> if (isPackageDirectory(e) && e.parentDirectory?.findSubdirectory(name)?.takeIf { it != e } != null) {
                    add(conflicts, e, "Directory ${e.parentDirectory?.virtualFile?.presentableUrl} already contains '$name'")
                }
            }
        }
    }

    private fun packageNameConflicts(clause: GoPackageClause, newName: String, conflicts: MultiMap<PsiElement, String>) {
        val old = clause.name ?: return
        if (old == newName) return
        val dir = clause.containingFile?.containingDirectory ?: return
        val model = GoPackageModel.getInstance(clause.project)
        for (spec in importersOf(dir, withSubpackages = false)) {
            if (spec.alias != null) continue
            val file = spec.containingFile as? GoFile ?: continue
            val clash = file.imports.firstOrNull { it != spec && !it.isBlank && !it.isDot && GoScopes.importName(it) == newName }
            if (clash != null) add(conflicts, spec, "File ${file.name} already imports \"${clash.path}\" as '$newName'")
            if (model.scopeOf(file).lookup(newName).isNotEmpty()) add(conflicts, spec, "'$newName' is already declared in package ${file.packageName} of ${file.name}")
        }
    }

    private fun add(conflicts: MultiMap<PsiElement, String>, at: PsiElement, message: String) {
        if (message !in conflicts[at]) conflicts.putValue(at, message)
    }

    companion object {
        /** The last state of the "Rename directory" / "Rename package" check box ([GoRenameChoice.ASK]); on until the user unchecks it. */
        @Volatile var askedLinked = true

        /** The primary package clause of an unaliased import of a project package (the target of renaming `pkg` in `pkg.X`), else null. */
        fun importedClause(spec: GoImportSpec): GoPackageClause? {
            if (spec.alias != null || spec.path == "C") return null
            val file = spec.containingFile as? GoFile ?: return null
            val pkg = GoPackageModel.getInstance(spec.project).resolveImport(spec.path, file) ?: return null
            val dir = PsiManager.getInstance(spec.project).findDirectory(pkg.directory) ?: return null
            if (!inProjectContent(dir)) return null
            return primaryClause(dir, pkg.name ?: return null)
        }

        /** The clause whose rename carries the qualifier updates: the first non-test file of [dir] with `package name`. */
        private fun primaryClause(dir: PsiDirectory, name: String): GoPackageClause? =
            goFiles(dir).filter { it.packageName == name }.minWithOrNull(compareBy<GoFile>({ it.isTestFile }, { it.name }))?.packageClause

        private fun isPrimary(clause: GoPackageClause): Boolean {
            val dir = clause.containingFile?.containingDirectory ?: return false
            return primaryClause(dir, clause.name ?: return false) == clause
        }

        private fun goFiles(dir: PsiDirectory): List<GoFile> = dir.files.filterIsInstance<GoFile>()

        private fun addClauses(dir: PsiDirectory, old: String, newName: String, allRenames: MutableMap<PsiElement, String>) {
            val xOld = old + "_test"
            for (file in goFiles(dir)) {
                val clause = file.packageClause ?: continue
                when (file.packageName) {
                    old -> allRenames[clause] = newName
                    xOld -> if (!old.endsWith("_test")) allRenames[clause] = newName + "_test"
                }
            }
        }

        internal fun inProjectContent(dir: PsiDirectory?): Boolean {
            val vf = dir?.virtualFile ?: return false
            val index = ProjectFileIndex.getInstance(dir.project)
            return vf.isWritable && index.isInContent(vf) && !index.isInLibrary(vf)
        }

        private fun importPathOf(dir: PsiDirectory): String? = GoPackageResolver.getInstance(dir.project).importPathOf(dir.virtualFile)

        /** A directory inside a module (not its root) with `.go` files in it or below: renaming it changes import paths. */
        private fun isPackageDirectory(dir: PsiDirectory): Boolean {
            if (!inProjectContent(dir) || dir.virtualFile.findChild("go.mod") != null) return false
            val path = importPathOf(dir) ?: return false
            return path.contains('/') && hasGoFiles(dir.virtualFile)
        }

        private fun hasGoFiles(root: VirtualFile): Boolean {
            var found = false
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (!file.isDirectory && file.extension == "go") found = true
                    return !found
                }
            })
            return found
        }

        /** Import specs in the project that resolve to [dir] (or, [withSubpackages], to a package below it). */
        private fun importersOf(dir: PsiDirectory, withSubpackages: Boolean): List<GoImportSpec> {
            val project = dir.project
            val path = importPathOf(dir) ?: return emptyList()
            val keys = if (!withSubpackages) listOf(path)
            else FileBasedIndex.getInstance().getAllKeys(GoFileImportsIndex.NAME, project).filter { it == path || it.startsWith("$path/") }
            val scope = GlobalSearchScope.projectScope(project)
            val model = GoPackageModel.getInstance(project)
            val psiManager = PsiManager.getInstance(project)
            val result = ArrayList<GoImportSpec>()
            for (key in keys.distinct()) {
                for (vf in GoFileImportsIndex.filesImporting(key, project, scope)) {
                    val file = psiManager.findFile(vf) as? GoFile ?: continue
                    for (spec in file.imports) {
                        if (spec.path != key) continue
                        val target = model.resolveImport(key, file)?.directory ?: continue
                        val matches = if (withSubpackages) VfsUtilCore.isAncestor(dir.virtualFile, target, false) else target == dir.virtualFile
                        if (matches) result += spec
                    }
                }
            }
            return result
        }

        /** `old.X` qualifiers in files importing the package of [clause] without an alias; only the primary clause reports them. */
        private fun qualifierReferences(clause: GoPackageClause): List<PsiReference> {
            if (!isPrimary(clause)) return emptyList()
            val dir = clause.containingFile?.containingDirectory ?: return emptyList()
            val result = ArrayList<PsiReference>()
            for (spec in importersOf(dir, withSubpackages = false)) {
                if (spec.alias != null) continue
                val localName = GoScopes.importName(spec)
                for (ref in PsiTreeUtil.findChildrenOfType(spec.containingFile, GoReferenceExpression::class.java)) {
                    if (ref.expression != null || ref.identifier.text != localName) continue
                    val reference = ref.reference ?: continue
                    if (reference.isReferenceTo(spec)) result += reference
                }
            }
            return result
        }
    }
}

/** Packages outside the project content (GOROOT, module cache) are not renamed. */
class GoLibraryPackageRenameVeto : Condition<PsiElement> {
    override fun value(element: PsiElement): Boolean =
        element is GoPackageClause && GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project) &&
            element.containingFile?.virtualFile != null && !GoRenamePackageProcessor.inProjectContent(element.containingFile?.containingDirectory)
}
