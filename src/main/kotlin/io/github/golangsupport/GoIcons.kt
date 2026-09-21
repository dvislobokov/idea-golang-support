package io.github.golangsupport

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The SVGs are written by `tools/icons/generate.py`, where the purposes of the icons are listed next to their counterparts of the .NET plugin. */
object GoIcons {
    @JvmField val File: Icon = load("go")
    @JvmField val TestFile: Icon = load("goTest")

    /** Code written by a generator: `*.pb.go`, `*_gen.go`, `zz_generated.*`. */
    @JvmField val Generated: Icon = load("goGenerated")

    /** `text/template` and `html/template` files. */
    @JvmField val Template: Icon = load("goTemplate")

    /** Configuration of the tools of the ecosystem: golangci-lint, goreleaser, air. */
    @JvmField val Config: Icon = load("goConfig")

    /** What the toolchain produces: `*.test`, `__debug_bin*`. */
    @JvmField val Binary: Icon = load("goBinary")

    /** The glyph of the New | Go File dialog. */
    @JvmField val New: Icon = load("goNew")

    /** The run configuration. */
    @JvmField val Run: Icon = load("goRun")

    @JvmField val Module: Icon = load("goMod")
    @JvmField val Workspace: Icon = load("goWork")
    @JvmField val Sum: Icon = load("goSum")

    /** A dependency in the project tree; an indirect one is there because something else needs it. */
    @JvmField val Package: Icon = load("goPackage")
    @JvmField val IndirectPackage: Icon = load("goPackageIndirect")

    /** A `vendor` directory that `go mod vendor` has made. */
    @JvmField val Vendor: Icon = load("goVendor")

    @JvmField val Benchmark: Icon = load("goBenchmark")
    @JvmField val Fuzz: Icon = load("goFuzz")
    @JvmField val Example: Icon = load("goExample")

    private val GENERATED_SUFFIXES = listOf(".pb.go", ".pb.gw.go", "_gen.go", ".gen.go", "_generated.go", "_string.go", ".sql.go")
    private val TEMPLATE_EXTENSIONS = setOf("tmpl", "gotmpl", "gohtml", "gotxt")
    private val CONFIG_NAMES = Regex("""\.golangci\.(ya?ml|toml|json)|\.goreleaser\.ya?ml|\.air\.toml|\.mockery\.ya?ml|sqlc\.(ya?ml|json)|buf(\.gen|\.work)?\.yaml""")

    /** The icon of a file of the Go world, by its name alone; null when the file should keep its regular icon. */
    fun forFile(fileName: String): Icon? {
        val name = fileName.lowercase()
        return when {
            name == "go.mod" -> Module
            name == "go.work" -> Workspace
            name == "go.sum" || name == "go.work.sum" -> Sum
            name.endsWith("_test.go") -> TestFile
            name.startsWith("zz_generated") && name.endsWith(".go") || GENERATED_SUFFIXES.any { name.endsWith(it) } -> Generated
            name.substringAfterLast('.', "") in TEMPLATE_EXTENSIONS -> Template
            CONFIG_NAMES.matches(name) -> Config
            name.startsWith("__debug_bin") || name.endsWith(".test") || name.endsWith(".test.exe") -> Binary
            else -> null
        }
    }

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", GoIcons::class.java)
}
