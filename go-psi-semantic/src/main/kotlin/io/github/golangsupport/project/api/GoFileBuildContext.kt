package io.github.golangsupport.project.api

/**
 * A build context under which a file excluded by the project's context builds: the file opened in the editor (`x_windows.go` on linux)
 * is checked against the package the go command would build for it, not against the files of another platform. Candidates are
 * the GOOS / GOARCH named by the file (name suffix, `//go:build` / `// +build` words) and the custom tags it mentions; the first
 * combination under which [GoBuildConstraintEvaluator.matchFile] holds wins. Null when none does (`//go:build ignore && !ignore`).
 */
internal object GoFileBuildContext {

    private val WORD = Regex("""[A-Za-z0-9_.]+""")
    private val FALLBACK_OS = listOf("linux", "windows", "darwin")

    /**
     * Words never offered as custom tags: `ignore` is the convention for files that are not part of the package (`//go:build ignore`
     * generator programs, often several `package main` files next to a library) and no build sets it; satisfying it merged those
     * programs into one package (golang.org/x/arch `*spec/spec.go`: worse resolve in the module-cache corpus). The rest are special tags.
     */
    private val NEVER_SET = setOf("ignore", "cgo", "test", "unix")

    fun contextFor(name: String, content: CharSequence, base: GoBuildContext): GoBuildContext? {
        if (GoBuildConstraintEvaluator.matchFile(name, content, base)) return base
        val header = GoBuildConstraintEvaluator.parseFileHeader(content)
        val words = LinkedHashSet<String>()
        (listOfNotNull(header.goBuild) + header.plusBuild).forEach { line -> WORD.findAll(line.removePrefix("//go:build").removePrefix("//")).forEach { words += it.value } }
        words.remove("build"); words.remove("go")
        name.substringBefore('.').split('_').drop(1).forEach { words += it }
        val oses = (words.filter { it in GoPlatforms.KNOWN_OS } + base.goos + FALLBACK_OS).distinct()
        val arches = (words.filter { it in GoPlatforms.KNOWN_ARCH } + base.goarch).distinct()
        val custom = words.filter { it !in GoPlatforms.KNOWN_OS && it !in GoPlatforms.KNOWN_ARCH && !it.startsWith("go1") && it !in NEVER_SET }
        val tagSets = listOf(base.buildTags, base.buildTags + custom).distinct()
        for (os in oses) for (arch in arches) for (tags in tagSets) {
            val context = base.copy(goos = os, goarch = arch, buildTags = tags)
            if (GoBuildConstraintEvaluator.matchFile(name, content, context)) return context
        }
        return null
    }
}
