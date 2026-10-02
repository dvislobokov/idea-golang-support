package io.github.golangsupport.ide.editor

/** Go text for the editing tests: the margin goes, and every four spaces of indentation become a tab, as gofmt writes them. */
internal fun go(text: String): String = text.trimIndent().lines().joinToString("\n") { line ->
    val spaces = line.takeWhile { it == ' ' }.length
    "\t".repeat(spaces / 4) + " ".repeat(spaces % 4) + line.substring(spaces)
} + "\n"
