package io.github.golangsupport.project.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * A Go package: one directory, partitioned for a [GoBuildContext].
 *
 * @property importPath the import path (`vendor/`-prefixed for packages vendored into GOROOT/src,
 *   as `go list std` prints them); null when it cannot be computed (a directory outside any module,
 *   GOROOT or GOPATH).
 * @property name the package name of [goFiles] (or of [testFiles] for test-only packages).
 * @property directory the package directory.
 * @property module the module providing it; null for the standard library and GOPATH packages.
 * @property goFiles non-test `.go` files that match the build context.
 * @property testFiles `_test.go` files of the same package that match the build context.
 * @property xTestFiles `_test.go` files of the external test package (`<name>_test`).
 * @property ignoredFiles `.go` files excluded by build constraints, file name rules or a
 *   mismatching package clause.
 * @property isCommand whether the package is `main`.
 * @property isStd whether the package lives in `$GOROOT/src`.
 */
data class GoPackage(
    val importPath: String?,
    val name: String?,
    val directory: VirtualFile,
    val module: GoModule?,
    val goFiles: List<VirtualFile>,
    val testFiles: List<VirtualFile>,
    val xTestFiles: List<VirtualFile>,
    val ignoredFiles: List<VirtualFile>,
    val isStd: Boolean,
) {
    val isCommand: Boolean get() = name == "main"

    /** True when the directory has no buildable `.go` file for the context (tests included). */
    val isEmpty: Boolean get() = goFiles.isEmpty() && testFiles.isEmpty() && xTestFiles.isEmpty()
}

/** The result of [GoPackageResolver.resolveImport]. */
sealed interface GoImportResolution {
    /** The import resolves to [pkg]. */
    data class Resolved(val pkg: GoPackage) : GoImportResolution

    /** `import "C"`: the cgo pseudo-package; there is no directory. */
    data object CPseudoPackage : GoImportResolution

    /** The package exists but `internal` visibility rules forbid importing it from the importer. */
    data class InternalDenied(val pkg: GoPackage) : GoImportResolution

    /** No directory provides the import path; [reason] is a human-readable explanation. */
    data class Unresolved(val importPath: String, val reason: String) : GoImportResolution

    /** The package for [Resolved] and [InternalDenied], null otherwise. */
    val packageOrNull: GoPackage?
        get() = when (this) {
            is Resolved -> pkg
            is InternalDenied -> pkg
            else -> null
        }
}

/**
 * Maps import paths to package directories and directories to packages.
 *
 * Resolution order for [resolveImport]: `C`; relative imports; the GOROOT vendor directory for
 * importers inside GOROOT; the standard library (`$GOROOT/src/<path>`); then, in the importer's
 * module graph, the vendor directory (vendor mode) or the module providing the longest matching
 * path prefix (main modules, workspace members, local `replace` targets, module cache); finally
 * `GOPATH/src`. `internal` visibility is checked on the result.
 */
interface GoPackageResolver {
    /** Resolves [importPath] as written in [fromFile] (a `.go` file or its directory). */
    fun resolveImport(importPath: String, fromFile: VirtualFile): GoImportResolution

    /**
     * The package in [directory] partitioned for [context] (the toolchain's build context when
     * null); null when the directory has no `.go` files.
     */
    fun packageOf(directory: VirtualFile, context: GoBuildContext? = null): GoPackage?

    /** Java-friendly overload of [packageOf] with the toolchain's build context. */
    fun packageOf(directory: VirtualFile): GoPackage? = packageOf(directory, null)

    /** The import path of the package containing [fileOrDirectory], null when unknown. */
    fun importPathOf(fileOrDirectory: VirtualFile): String?

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GoPackageResolver = project.service()
    }
}
