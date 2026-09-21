package io.github.golangsupport.view

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.SimpleTextAttributes
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModule
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.mod.GoReplace
import io.github.golangsupport.mod.GoRequire
import java.io.File
import javax.swing.Icon

/** A "Dependencies" node in every directory of the Project view that has a go.mod: what the module requires, with the sources from the module cache. */
class GoDependenciesTreeProvider : TreeStructureProvider, DumbAware {
    override fun modify(parent: AbstractTreeNode<*>, children: MutableCollection<AbstractTreeNode<*>>, settings: ViewSettings?): Collection<AbstractTreeNode<*>> {
        val directory = (parent as? PsiDirectoryNode)?.virtualFile ?: return children
        val project = parent.project ?: return children
        if (directory.findChild(GoModFileType.GO_MOD) == null || isInModuleCache(directory)) return children
        val module = GoModulesService.getInstance(project).moduleOf(directory)?.takeIf { it.root == directory } ?: return children
        if (module.content.requires.isEmpty()) return children
        return children + GoDependenciesNode(project, module, settings)
    }

    /** The modules of the cache have a go.mod too, and showing their dependencies would make the tree endless. */
    private fun isInModuleCache(directory: VirtualFile): Boolean = GoEnvironment.quick().goModCache?.let { directory.path.startsWith(it.replace('\\', '/')) } == true
}

class GoDependenciesNode(project: Project, private val module: GoModule, private val settings: ViewSettings?) : AbstractTreeNode<GoModule>(project, module) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val content = module.content
        val direct = content.directRequires.map { GoDependencyNode(myProject, module, it, settings) }
        val indirect = content.indirectRequires
        return if (indirect.isEmpty()) direct else direct + GoIndirectDependenciesNode(myProject, module, indirect, settings)
    }

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.PpLibFolder)
        presentation.addText("Dependencies", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        module.content.goVersion?.let { presentation.addText("  Go $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }

    override fun getWeight(): Int = Int.MAX_VALUE
    override fun canNavigate(): Boolean = true
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(myProject, module.modFile).navigate(requestFocus)
}

class GoIndirectDependenciesNode(project: Project, private val module: GoModule, private val requires: List<GoRequire>, private val settings: ViewSettings?) :
    AbstractTreeNode<String>(project, "indirect:" + module.root.path) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = requires.map { GoDependencyNode(myProject, module, it, settings) }

    override fun update(presentation: PresentationData) {
        presentation.setIcon(AllIcons.Nodes.PpLibFolder)
        presentation.addText("Indirect", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        presentation.addText("  ${requires.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun getWeight(): Int = Int.MAX_VALUE
}

/** `github.com/x/y v1.2.3`; opens into the directory the code comes from, double click goes to the line of go.mod. */
class GoDependencyNode(project: Project, private val module: GoModule, private val require: GoRequire, private val settings: ViewSettings?) :
    AbstractTreeNode<String>(project, module.root.path + "|" + require.path) {
    private val replace: GoReplace? get() = module.content.replacementOf(require)

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val directory = sourceDirectory(module, require, GoEnvironment.quick().goModCache)?.let { LocalFileSystem.getInstance().findFileByIoFile(it) } ?: return emptyList()
        val psiDirectory = PsiManager.getInstance(myProject).findDirectory(directory) ?: return emptyList()
        return PsiDirectoryNode(myProject, psiDirectory, settings).children
    }

    override fun update(presentation: PresentationData) {
        presentation.setIcon(GoIcons.Package)
        presentation.addText(require.path, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        presentation.addText("  " + require.version, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        replace?.let { presentation.addText("  => " + listOfNotNull(it.newPath, it.newVersion).joinToString(" "), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(myProject, module.modFile, require.line, 0).navigate(requestFocus)

    companion object {
        /** A local replacement is a directory next to the module, anything else lives in the module cache (after `go mod download`). */
        fun sourceDirectory(module: GoModule, require: GoRequire, moduleCache: String?): File? {
            val replace = module.content.replacementOf(require)
            if (replace != null && replace.isLocal) return File(replace.newPath).let { if (it.isAbsolute) it else File(module.root.path, replace.newPath) }.takeIf { it.isDirectory }
            val cached = GoModFile.cachePath(replace?.newPath ?: require.path, replace?.newVersion ?: require.version)
            return moduleCache?.let { File(it, cached) }?.takeIf { it.isDirectory }
        }
    }
}

/** go.sum is plain text and keeps being it; test files get a mark of their own. */
class GoFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? = when {
        file.isDirectory -> null
        file.name == "go.sum" -> GoIcons.Module
        file.name.endsWith(GoFile.TEST_SUFFIX) -> GoIcons.TestFile
        else -> null
    }
}
