package io.github.golangsupport.lang

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.maddyhome.idea.copyright.CopyrightProfile
import com.maddyhome.idea.copyright.options.LanguageOptions
import com.maddyhome.idea.copyright.psi.UpdateAnyFileCopyright
import com.maddyhome.idea.copyright.psi.UpdateCopyright
import com.maddyhome.idea.copyright.psi.UpdateCopyrightsProvider

/**
 * The copyright notice of Go files (META-INF/go-copyright.xml, only where the IDE has the Copyright plugin): what makes Generate |
 * Copyright and Code | Update Copyright work in a Go file. The notice goes above the package clause as `//` lines, as Go sources write
 * it (a build constraint after it still works: `//go:build` only has to come before the package clause).
 */
class GoUpdateCopyrightsProvider : UpdateCopyrightsProvider() {
    override fun createInstance(project: Project, module: Module?, file: VirtualFile, base: FileType, options: CopyrightProfile): UpdateCopyright =
        UpdateAnyFileCopyright(project, module, file, options)

    override fun getDefaultOptions(): LanguageOptions = createDefaultOptions(false).apply { isBlock = false }
}
