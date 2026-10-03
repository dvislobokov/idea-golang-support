package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoPackageModel

/** Why a selection cannot be moved; the handler shows the message as an error hint. */
class GoMoveRefusal(message: String) : RuntimeException(message)

/**
 * One piece of text Move takes out of a source file: a function or method declaration, a whole `type`/`var`/`const` declaration,
 * or one spec of a group (which becomes its own declaration in the target, `keyword` + spec). [range] includes the doc comment.
 */
class GoMoveUnit(val element: PsiElement, val file: GoFile, val range: TextRange, val keyword: String?, val names: List<GoNamedElement>) {
    val types: List<GoTypeSpec> get() = names.filterIsInstance<GoTypeSpec>()

    fun contains(e: PsiElement): Boolean = e.containingFile == file && range.contains(e.textRange)

    /** The declaration text for the target, from [raw] (the text of [range], already rewritten): a spec gets its keyword and loses one indent. */
    fun targetText(raw: String): String {
        if (keyword == null) return raw
        // the comments before the spec stay above the new declaration; the keyword goes before the spec itself. [raw] starts after
        // the indentation of its first line, so only the following lines lose the group's indent.
        val prefix = (element.textRange.startOffset - range.startOffset) + leadingComments(element)
        val cut = prefix.coerceIn(0, raw.length) // rewrites never touch the comments, so the prefix keeps its length
        return dedent(raw.substring(0, cut)) + "$keyword " + dedent(raw.substring(cut))
    }

    private fun dedent(s: String): String = s.lines().mapIndexed { i, l -> if (i == 0) l else l.removePrefix("\t") }.joinToString("\n")

    /** The length of the comments (and the whitespace after them) the parser bound into a spec ahead of its name. */
    private fun leadingComments(e: PsiElement): Int {
        var c = e.firstChild
        var length = 0
        while (c is PsiComment || c is PsiWhiteSpace) {
            length += c.textLength
            c = c.nextSibling
        }
        return length
    }
}

/**
 * The planning half of Move: which elements a caret or a selection means, and the [GoMoveUnit]s that carry them. A name of a
 * multi-name spec (`var a, b = 1, 2`) and a constant of an `iota` group (or of a group whose specs repeat the previous expression)
 * move only with the rest; a method moves to another package only with its receiver type, and a type takes its methods there.
 */
object GoMoveDeclarations {

    /** The package-level declaration [e] is or belongs to (the spec of a definition), or null. */
    fun movable(e: PsiElement?): PsiElement? {
        val element = when (e) {
            is GoFunctionDeclaration, is GoMethodDeclaration, is GoTypeSpec, is GoVarSpec, is GoConstSpec, is GoVarDefinition, is GoConstDefinition,
            is GoTypeDeclaration, is GoVarDeclaration, is GoConstDeclaration -> e
            else -> return null
        }
        if (element.containingFile !is GoFile || GoPsiUtil.functionOwner(element) != null) return null
        return element
    }

    /** The package-level declarations a selection [range] of [file] touches: a group selected whole, else the specs of it the selection touches. */
    fun inRange(file: GoFile, range: TextRange): List<PsiElement> {
        val result = ArrayList<PsiElement>()
        fun visit(parent: PsiElement) {
            for (c in parent.children) {
                if (!c.textRange.intersects(range) || c.textRange.endOffset == range.startOffset || c.textRange.startOffset == range.endOffset) continue
                when (c) {
                    is GoFunctionDeclaration, is GoMethodDeclaration -> result += c
                    is GoTypeDeclaration, is GoVarDeclaration, is GoConstDeclaration -> {
                        val specs = specsOf(c)
                        if (range.contains(c.textRange) || specs.size <= 1) result += c else result += specs.filter { it.textRange.intersects(range) }
                    }
                    else -> if (c !is PsiComment && c !is PsiWhiteSpace) visit(c)
                }
            }
        }
        visit(file)
        return result
    }

    fun specsOf(declaration: PsiElement): List<PsiElement> = when (declaration) {
        is GoTypeDeclaration -> declaration.typeSpecList
        is GoVarDeclaration -> declaration.varSpecList
        is GoConstDeclaration -> declaration.constSpecList
        else -> emptyList()
    }

    private fun namesOf(spec: PsiElement): List<GoNamedElement> = when (spec) {
        is GoTypeSpec -> listOf(spec)
        is GoVarSpec -> spec.varDefinitionList
        is GoConstSpec -> spec.constDefinitionList
        is GoFunctionDeclaration -> listOf(spec)
        else -> emptyList()
    }

    private fun keyword(declaration: PsiElement): String = declaration.firstChild.text

    /** `const ( A = iota; B )` or `const ( A = 1; B )`: the specs depend on their order, so the group moves as a whole. */
    private fun orderDependent(group: GoConstDeclaration): Boolean =
        group.constSpecList.size > 1 && group.constSpecList.any { it.expressionList.isEmpty() || it.text.contains("iota") }

    /**
     * The units for [elements] (all from one package), in source order. [withMethods]: the methods of every moved type go too,
     * wherever they are declared in the package (always so for another package: [crossPackage]).
     */
    fun units(elements: Collection<PsiElement>, withMethods: Boolean, crossPackage: Boolean): List<GoMoveUnit> {
        val chosen = elements.mapNotNull { movable(it) ?: throw GoMoveRefusal("Only package-level declarations can be moved") }
        if (chosen.isEmpty()) throw GoMoveRefusal("Nothing to move")
        // specs grouped by their declaration; functions and methods stand alone
        val specs = LinkedHashMap<PsiElement, LinkedHashSet<PsiElement>>()
        val single = LinkedHashSet<PsiElement>()
        for (e in chosen) {
            when (e) {
                is GoFunctionDeclaration, is GoMethodDeclaration -> single += e
                is GoTypeDeclaration, is GoVarDeclaration, is GoConstDeclaration -> specs.getOrPut(e) { LinkedHashSet() }.addAll(specsOf(e))
                is GoVarDefinition, is GoConstDefinition -> {
                    val spec = e.parent
                    val names = namesOf(spec)
                    if (names.size > 1 && !names.all { n -> chosen.contains(n) }) {
                        throw GoMoveRefusal("${(e as GoNamedElement).name} is declared together with ${names.filter { it != e }.joinToString { it.name.orEmpty() }}; move the whole spec")
                    }
                    specs.getOrPut(spec.parent) { LinkedHashSet() } += spec
                }
                else -> specs.getOrPut(e.parent) { LinkedHashSet() } += e
            }
        }
        for ((declaration, moved) in specs) {
            if (declaration is GoConstDeclaration && orderDependent(declaration) && moved.size < declaration.constSpecList.size) {
                val name = namesOf(moved.first()).firstOrNull()?.name ?: "constant"
                throw GoMoveRefusal("$name belongs to a const group whose values depend on their order (iota or repeated expressions); move the whole group")
            }
        }
        val units = ArrayList<GoMoveUnit>()
        for ((declaration, moved) in specs) {
            val file = declaration.containingFile as GoFile
            if (moved.size == specsOf(declaration).size) {
                units += GoMoveUnit(declaration, file, withDoc(declaration), null, moved.flatMap(::namesOf))
            } else {
                for (spec in moved) units += GoMoveUnit(spec, file, withDoc(spec), keyword(declaration), namesOf(spec))
            }
        }
        for (e in single) units += GoMoveUnit(e, e.containingFile as GoFile, withDoc(e), null, if (e is GoFunctionDeclaration) listOf(e) else emptyList())
        val types = units.flatMap { it.types }
        if (withMethods || crossPackage) {
            for (type in types) {
                val name = type.name ?: continue
                for (method in GoPackageModel.getInstance(type.project).scopeOf(type.containingFile as GoFile).methodsOf(name)) {
                    if (method.containingFile !is GoFile || units.any { it.element == method }) continue
                    units += GoMoveUnit(method, method.containingFile as GoFile, withDoc(method), null, emptyList())
                }
            }
        }
        if (crossPackage) {
            val typeNames = types.mapNotNull { it.name }.toSet()
            for (u in units) {
                val m = u.element as? GoMethodDeclaration ?: continue
                if (m.receiverTypeName !in typeNames) throw GoMoveRefusal("Method ${m.receiverTypeName}.${m.name} must stay in the package of ${m.receiverTypeName}; move the type with it")
            }
        }
        val first = units.first().file
        if (units.any { it.file.containingDirectory != first.containingDirectory || it.file.packageName != first.packageName }) {
            throw GoMoveRefusal("The declarations to move must belong to one package")
        }
        return units.distinctBy { it.element }.sortedWith(compareBy({ it.file.virtualFile?.path ?: it.file.name }, { it.range.startOffset }))
    }

    /** The range of [e] grown by the comment lines directly above it when the parser left them outside (a doc comment is usually bound in). */
    fun withDoc(e: PsiElement): TextRange {
        var start = e.textRange.startOffset
        var p = e.prevSibling
        val text = e.containingFile.viewProvider.contents
        while (p != null) {
            if (p is PsiWhiteSpace && p.text.count { it == '\n' } <= 1) {
                p = p.prevSibling
                continue
            }
            if (p !is PsiComment) break
            var lineStart = p.textRange.startOffset
            while (lineStart > 0 && (text[lineStart - 1] == ' ' || text[lineStart - 1] == '\t')) lineStart--
            if (lineStart > 0 && text[lineStart - 1] != '\n') break
            start = p.textRange.startOffset
            p = p.prevSibling
        }
        return TextRange(start, e.textRange.endOffset)
    }

    /** Short names of what moves, for the dialog and the usage view. */
    fun describe(units: List<GoMoveUnit>): List<String> = units.map { u ->
        when (val e = u.element) {
            is GoMethodDeclaration -> "func (${e.receiverTypeName}) ${e.name}"
            is GoFunctionDeclaration -> "func ${e.name}"
            else -> (u.keyword ?: keyword(e)) + " " + u.names.joinToString { it.name.orEmpty() }
        }
    }

    internal fun isTopLevel(e: PsiElement): Boolean =
        (e is GoFunctionDeclaration || e is GoTypeSpec || e is GoVarDefinition || e is GoConstDefinition) && GoPsiUtil.functionOwner(e) == null &&
            PsiTreeUtil.getParentOfType(e, GoFile::class.java) != null
}
