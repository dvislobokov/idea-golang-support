package io.github.golangsupport.lang

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * [GoIdioms.Types] over the PSI of a go-psi file, for the grey text of [GoInlineIdiomsProvider]. The suggestion is asked right after a
 * keystroke, before the document is committed: the PSI is that of the last committed text, and an offset is used only while the text
 * up to it is the same in both (what was typed at the caret moves nothing before it). Call under a read action.
 */
class GoIdiomTypes private constructor(private val file: GoFile, private val sameUpTo: Int, caret: Int) : GoIdioms.Types {
    private val service = GoSemanticService.getInstance(file.project)

    override val function: GoIdioms.Function? by lazy { leafBefore(caret)?.let(GoReturnValues::function) }

    override fun assignsError(lineStart: Int): Boolean? {
        val statement = statementAt(lineStart) ?: return null
        val holder = if (statement is GoShortVarDeclaration || statement is GoAssignmentStatement) statement
        else PsiTreeUtil.findChildOfAnyType(statement, GoShortVarDeclaration::class.java, GoAssignmentStatement::class.java)
        val values = when (val s = holder) {
            is GoShortVarDeclaration -> s.expressionList
            is GoAssignmentStatement -> s.expressionList
            else -> return null
        }
        val call = values.singleOrNull() as? GoCallExpr ?: return null
        val results = service.calleeSignature(call)?.results ?: return null
        return results.lastOrNull()?.let { GoReturnValues.isError(it.type) } ?: false
    }

    override fun hasMethod(lineStart: Int, receiver: String, method: String, returnsError: Boolean): Boolean? {
        val statement = statementAt(lineStart) ?: return null
        val type = receiverType(statement, receiver) ?: return null
        if (type is GoUnknownType) return null
        // a variable is addressable: the methods of the pointer are called on it as well
        val selection = (service.lookupFieldOrMethod(type, method, file) ?: if (type !is GoPointerType) service.lookupFieldOrMethod(GoPointerType(type), method, file) else null)
            as? GoLookup.Selection.Method ?: return false
        val signature = selection.method.signature
        if (signature.params.isNotEmpty()) return false
        return if (returnsError) signature.results.size == 1 && GoReturnValues.isError(signature.results[0].type) else signature.results.isEmpty()
    }

    /** The type of [receiver] as the statement has it: a variable it declares, or an expression written in it. */
    private fun receiverType(statement: PsiElement, receiver: String): GoType? {
        PsiTreeUtil.findChildrenOfType(statement, GoVarDefinition::class.java).firstOrNull { it.name == receiver }?.let { return service.declarationType(it) }
        return PsiTreeUtil.findChildrenOfType(statement, GoExpression::class.java).firstOrNull { it.text == receiver }?.let(service::typeOf)
    }

    override fun selectCases(lineStart: Int): List<String>? {
        val place = statementAt(lineStart) ?: return null
        val locals = GoReturnValues.localVariables(place)
        var context: String? = null
        val timers = ArrayList<String>()
        val channels = ArrayList<String>()
        for (variable in locals) {
            val type = service.declarationType(variable)
            val name = variable.name ?: continue
            when {
                isNamed(type, "context", "Context") -> if (context == null) context = name
                type is GoPointerType && (isNamed(type.elem, "time", "Timer") || isNamed(type.elem, "time", "Ticker")) -> timers += name
                (type.underlying() as? GoChanType)?.let { it.dir != GoChanDir.SEND } == true -> channels += name
            }
        }
        if (context == null && channels.isEmpty() && timers.isEmpty()) return null
        val lines = ArrayList<String>()
        context?.let { lines += "case <-$it.Done():"; lines += "\t" + GoIdioms.CONTEXT_EXIT + it }
        timers.forEach { lines += "case <-$it.C:" }
        channels.forEach { lines += "case v := <-$it:" }
        return lines
    }

    override fun switchCases(lineStart: Int): List<String>? {
        val statement = statementAt(lineStart) ?: return null
        return when (statement) {
            is GoExprSwitchStatement -> if (statement.exprCaseClauseList.isEmpty()) GoPsiUtil.run { statement.tag }?.let(::constantCases) else null
            is GoTypeSwitchStatement -> if (statement.typeCaseClauseList.isEmpty()) GoPsiUtil.run { statement.guard }?.expression?.let(::implementationCases) else null
            else -> null
        }
    }

    /** `case A:` for every constant of the named type of [tag], in the order of declaration (the file of the type first). */
    private fun constantCases(tag: GoExpression): List<String>? {
        val type = service.typeOf(tag) as? GoNamedType ?: return null
        if (type.underlying() !is GoBasicType || type.pkgPath == null) return null
        val declaring = type.declaration.containingFile as? GoFile ?: return null
        val files = declaring.containingDirectory?.files?.filterIsInstance<GoFile>()?.sortedBy { it.name }.orEmpty()
        val ordered = listOf(declaring) + files.filter { it != declaring && it.packageName == declaring.packageName && !it.isTestFile }
        val own = declaring.viewProvider.virtualFile.parent == GoPsiUtil.originalFile(file).viewProvider.virtualFile.parent
        val qualifier = if (own) "" else (GoReturnValues.importName(file, type.pkgPath ?: return null) ?: return null) + "."
        val names = ordered.flatMap { f -> f.consts }.filter { c ->
            val name = c.name
            name != null && name != "_" && (own || c.isPublic()) && GoTypePredicates.identical(service.declarationType(c), type)
        }.mapNotNull { it.name }.distinct()
        return names.takeIf { it.isNotEmpty() }?.map { "case $qualifier$it:" }
    }

    /** `case *T:` / `case T:` for every type of the project that implements the interface of [subject]. */
    private fun implementationCases(subject: GoExpression): List<String>? {
        if (DumbService.isDumb(file.project)) return null
        val type = service.typeOf(subject) as? GoNamedType ?: return null
        val iface = type.underlying() as? GoInterfaceType ?: return null
        if (iface.isEmpty) return null
        val specs = GoImplementations.implementingTypes(type.declaration, GlobalSearchScope.projectScope(file.project), MAX_CASES)
        val cases = specs.mapNotNull { spec ->
            if (spec.typeParameters != null) return@mapNotNull null
            val implementation = service.declarationType(spec) as? GoNamedType ?: return@mapNotNull null
            if (implementation.underlying() is GoInterfaceType) return@mapNotNull null
            val text = GoReturnValues.typeText(implementation, file)
            when {
                service.implements(implementation, iface) -> "case $text:"
                service.implements(GoPointerType(implementation), iface) -> "case *$text:"
                else -> null
            }
        }.distinct()
        return cases.takeIf { it.isNotEmpty() }
    }

    private fun isNamed(type: GoType, path: String, name: String): Boolean = type is GoNamedType && type.name == name && type.pkgPath == path

    /** The statement that begins on the line at [lineStart] (the offsets of the document), when the PSI has that line as it is. */
    private fun statementAt(lineStart: Int): PsiElement? {
        val text = file.node.chars
        var start = lineStart
        while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
        if (start >= sameUpTo) return null
        var element: PsiElement? = file.findElementAt(start) ?: return null
        var found: PsiElement? = null
        while (element != null && element !is PsiFile && element.textRange.startOffset == start) {
            if (element is GoStatement) found = element
            element = element.parent
        }
        return found
    }

    private fun leafBefore(offset: Int): PsiElement? {
        val at = minOf(offset, sameUpTo) - 1
        return if (at < 0) null else file.findElementAt(at)
    }

    companion object {
        private const val MAX_CASES = 50

        /** The types of [psiFile] at [offset] of [document]; null when it is not a go-psi file, in dumb mode, or outside a function. */
        fun of(psiFile: PsiFile, document: Document, offset: Int): GoIdiomTypes? {
            val file = psiFile as? GoFile ?: return null
            if (DumbService.isDumb(file.project)) return null
            val documents = PsiDocumentManager.getInstance(file.project)
            val committed = documents.getLastCommittedText(document)
            val current = document.immutableCharSequence
            // the PSI is that of the committed text; a file whose PSI is behind even that is not asked
            if (file.textLength != committed.length) return null
            val same = if (documents.isCommitted(document)) current.length else StringUtil.commonPrefixLength(committed, current)
            val types = GoIdiomTypes(file, same, offset)
            return types.takeIf { types.leafBefore(offset)?.let(GoPsiUtil::functionOwner) != null }
        }
    }
}
