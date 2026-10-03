package io.github.golangsupport.ide.inspections

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * Which Go files the analysis reports on: project sources only. Skipped: files outside the project content or in a library root
 * (GOROOT, the module cache), directories the `go` command does not build or that hold someone else's code (`vendor`, `testdata`,
 * `node_modules`, names starting with `.` or `_`, below the content root), and generated files (`// Code generated … DO NOT EDIT.`
 * before the package clause, https://go.dev/s/generatedcode). The same rules as the headless `go-inspect` walk of the host.
 * Text and VFS only: no AST, no index, so it is safe in dumb mode.
 */
object GoAnalysisScope {

    /** Whether [file] is analysed at all. */
    fun isAnalysed(file: PsiFile): Boolean {
        val go = file as? GoFile ?: return false
        val vf = GoPsiUtil.originalVirtualFile(go)
        return isProjectSource(go, vf) && !isGenerated(go.viewProvider.contents)
    }

    private fun isProjectSource(file: GoFile, vf: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(file.project)
        if (!index.isInContent(vf) || index.isInLibrary(vf)) return false
        val root = index.getContentRootForFile(vf)
        var dir = vf.parent
        while (dir != null && dir != root) {
            if (isSkippedDirectory(dir.name)) return false
            dir = dir.parent
        }
        return true
    }

    /** `go` ignores `testdata` and directories starting with `.` or `_`; `vendor` and `node_modules` are someone else's code. */
    fun isSkippedDirectory(name: String): Boolean = name == "vendor" || name == "testdata" || name == "node_modules" || name.startsWith(".") || name.startsWith("_")

    private val GENERATED = Regex("""^// Code generated .* DO NOT EDIT\.$""")

    /** The `go generate` marker on a line of its own anywhere before the package clause. */
    fun isGenerated(text: CharSequence): Boolean {
        for (line in text.lineSequence()) {
            val trimmed = line.trimEnd('\r')
            if (GENERATED.matches(trimmed)) return true
            if (trimmed.startsWith("package ") || trimmed == "package") return false
        }
        return false
    }

    /** [isGenerated] of a PSI file; false for anything but a Go file. */
    fun isGenerated(file: PsiFile?): Boolean = file is GoFile && isGenerated(file.viewProvider.contents)
}
