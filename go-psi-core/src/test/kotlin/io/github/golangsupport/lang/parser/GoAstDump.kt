package io.github.golangsupport.lang.parser

import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads an `astdump ast` S-expression file (`(Kind start-end extras)`, two spaces of indent per depth)
 * into an [AstNode] tree. Comment groups (dumped after the file node at depth 0) are ignored, and
 * `EmptyStmt` nodes are dropped: go-psi has no node for an empty statement (see [GoAstMapping]).
 * `BasicLit` keeps only its kind, `Ident` its name.
 */
object GoAstDump {
    private val line = Regex("""^( *)\((\w+) (-?\d+)-(-?\d+)(?: (.*))?\)$""")

    /** Returns null when the dump contains `ERROR` lines (a file go/parser rejected). */
    fun read(file: Path): AstNode? {
        var root: AstNode? = null
        val stack = ArrayList<AstNode>()
        for (l in Files.readAllLines(file)) {
            if (l.startsWith("ERROR ")) return null
            val m = line.matchEntire(l) ?: continue
            val depth = m.groupValues[1].length / 2
            val kind = m.groupValues[2]
            if (depth == 0 && root != null) break // comment groups
            var extra: String? = m.groups[5]?.value
            if (kind == "BasicLit" && extra != null) extra = extra.substringBefore(' ')
            val node = AstNode(kind, m.groupValues[3].toInt(), m.groupValues[4].toInt(), extra)
            while (stack.size > depth) stack.removeAt(stack.lastIndex)
            if (depth == 0) {
                root = node
            } else if (kind == "EmptyStmt") {
                continue
            } else {
                stack.last().children += node
            }
            stack += node
        }
        return root
    }
}
