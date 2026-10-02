package io.github.golangsupport.templates

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.DefaultTemplatePropertiesProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModulesService
import java.io.File
import java.util.Properties

/** What a new file of a directory starts with. */
object GoPackageNames {
    /** The package the other files of the directory declare; without them, the name of the directory made an identifier (`my-app` -> `myapp`), `main` under `cmd`. */
    fun forDirectory(existingPackages: List<String>, directoryName: String, parentName: String?): String {
        existingPackages.firstOrNull { !it.endsWith("_test") }?.let { return it }
        if (parentName == "cmd" || directoryName == "cmd") return "main"
        val identifier = directoryName.lowercase().filter { it.isLetterOrDigit() || it == '_' }.trimStart { it.isDigit() }
        return identifier.ifEmpty { "main" }
    }

    /** Asked on EDT, by the New File dialog: one file is enough, and it is read the way the editor reads files (a stream is a slow operation there). */
    fun of(directory: VirtualFile): String {
        val sibling = directory.children.filter { !it.isDirectory && it.extension == "go" }.minByOrNull { it.name.endsWith("_test.go") }
        val existing = sibling?.let { file -> runCatching { GoFileHeaderScanner.scan(LoadTextUtil.loadText(file), withImports = false).packageName }.getOrNull() }
        return forDirectory(listOfNotNull(existing?.removeSuffix("_test")), directory.name, directory.parent?.name)
    }
}

/** New | Go File: an empty file of the package of the directory, a program, or a test. */
class CreateGoFileAction : CreateFileFromTemplateAction("Go File", "Create a new Go file", GoIcons.File), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Go File")
            .addKind("Empty file", GoIcons.New, EMPTY)
            .addKind("Program (func main)", GoIcons.Run, MAIN)
            .addKind("Test", GoIcons.TestFile, TEST)
    }

    override fun getActionName(directory: PsiDirectory, newName: String, templateName: String): String = "Create Go File $newName"

    /** A test lives in `*_test.go` whatever was typed: the toolchain does not see it otherwise. */
    override fun createFile(name: String, templateName: String, dir: PsiDirectory): PsiFile? {
        val base = name.removeSuffix(".go")
        return super.createFile(if (templateName == TEST && !base.endsWith("_test")) base + "_test" else base, templateName, dir)
    }

    private companion object {
        const val EMPTY = "Go File"
        const val MAIN = "Go Program"
        const val TEST = "Go Test"
    }
}

/** `GO_PACKAGE` of the templates; the name of the first test is made by the template itself, from the name of the file. */
class GoTemplatePropertiesProvider : DefaultTemplatePropertiesProvider {
    override fun fillProperties(directory: PsiDirectory, props: Properties) {
        props.setProperty("GO_PACKAGE", GoPackageNames.of(directory.virtualFile))
    }
}

/** Menu Go | New Go Module: `go mod init` in the selected directory, or in the directory of the project. */
class NewGoModuleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val directory = directoryOf(e)
        val available = e.project != null && directory != null && directory.findChild(GoModFileType.GO_MOD) == null
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = available else e.presentation.isEnabled = available
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = directoryOf(e) ?: return
        val suggested = GoModulesService.getInstance(project).moduleOf(directory.parent)?.importPath(directory) ?: "example.com/${directory.name.lowercase()}"
        val path = Messages.showInputDialog(project, "Module path (what other modules import it by):", "New Go Module", GoIcons.Module, suggested, object : InputValidator {
            override fun checkInput(input: String): Boolean = input.isNotBlank() && input.none { it.isWhitespace() }
            override fun canClose(input: String): Boolean = checkInput(input)
        }) ?: return
        val commands = GoCli.commandLinesOrNotify(project, "Go Mod Init") { listOf(GoCli.commandLine(directory.path, "mod", "init", path.trim())) } ?: return
        GoCli.runInBackground(project, "Go Mod Init", commands, refresh = listOf(File(directory.path)))
    }

    private fun directoryOf(e: AnActionEvent): VirtualFile? {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return (if (selected != null && !selected.isDirectory) selected.parent else selected) ?: e.project?.guessProjectDir()
    }
}
