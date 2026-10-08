package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.intentions.GoFillStruct
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoScopeValues
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.types.GoField
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * "Mapping" completion: copying fields of one value into another, field by field, without a model. In a keyed or empty struct
 * literal (`dst := Dto{<caret>}`, `return Dto{Name: src.Name, <caret>}`) and on a fresh line after assignments `dst.Name = src.Name`,
 * every field of the target not written yet gets one item with the best expression in scope for it — a local, parameter or receiver,
 * or a field of one (`src.Name`, `u.Profile.Email`; the receiver's fields one level deeper) whose type is assignable to the field —
 * plus one item that writes every confident match at once, in declaration order.
 *
 * The score of a (field, expression) pair: the similarity of the names (exact, case-insensitive, one the prefix / suffix of the other
 * like `UserID` ↔ `ID`, the Jaccard overlap of their camel-case tokens), the "mapping partner" (the variable the neighbouring
 * assignments or elements copy from) and an identical type. Items go to the top of the list only when the context is clearly a mapping
 * (a previous assignment with the same partner, or an empty literal with a confident match); otherwise after the usual items.
 * Off with [GoCompletionAssistSettings.mappingEnabled]; nothing is computed then.
 */
object GoMappingCompletion {
    const val TAIL = " map"

    /** Score at which a pair is offered as an item, and at which "Map all" writes it. */
    const val OFFER = 0.4
    const val CONFIDENT = 0.7

    /** Below the Fill items (1 000 000), above every ordinary candidate. */
    private const val TOP_PRIORITY = 900_000.0
    /** Below everything the ordinary candidates get (their priorities are 0..3). */
    private const val BOTTOM_PRIORITY = -1.0

    /** Taken from a source whose name is another field's of the target. */
    const val OTHER_FIELD_PENALTY = 0.2

    private const val MAX_ROOTS = 25
    private const val MAX_FIELDS_PER_ROOT = 60
    private const val MAX_EXPRESSIONS = 400

    /** One candidate expression: its text, its type, and the root variable it starts from. */
    class Source(val text: String, val type: GoType, val root: String)

    /** The chosen expression of one field with its score. */
    class Match(val field: GoField, val source: Source, val score: Double) {
        val confident: Boolean get() = score >= CONFIDENT
    }

    /** What "Map all" needs: the matches in declaration order and the partner they were chosen with. */
    class Mapping(val matches: List<Match>, val partner: String?, val top: Boolean)

    // --- the literal ---

    /**
     * Items for the keyed or empty struct [literal] of type [literalType] (its unset fields minus [used]); [current] is the element
     * being typed in the completion copy.
     */
    fun collectLiteral(context: GoCompletionContext, literal: GoLiteralValue, literalType: GoType, current: GoElement?, used: Set<String>, result: CompletionResultSet) {
        if (!enabled(context) || context.keyOnly) return
        if (GoCompletionSemantics.derefUnderlying(literalType) !is GoStructType) return
        val members = GoMemberCandidates(context)
        // keys of a literal are the direct fields only (a promoted field is written through its embedded one)
        val fields = members.fieldEntries(literalType).filter { it.depth == 0 && it.field.name !in used && it.field.name != "_" }.map { it.field }
        if (fields.isEmpty()) return
        val elements = literal.elements.filter { it !== current }
        val partner = partnerOf(elements.mapNotNull { it.value?.expression })
        val excluded = assignedVariable(literal)
        val sources = sources(context, members, current ?: literal, excluded)
        val all = members.fieldEntries(literalType).mapTo(HashSet()) { it.field.name }
        val mapping = match(fields, sources, partner, top = elements.isEmpty() || partner != null, allFields = all) ?: return
        for ((i, m) in mapping.matches.withIndex()) {
            val text = "${m.field.name}: ${m.source.text}"
            result.addElement(prioritized(item(text, m.field.name, GoIdeIcons.FIELD, literalFieldHandler), mapping.top, i + 1))
        }
        mapAllLiteral(mapping)?.let { result.addElement(prioritized(it, mapping.top, 0)) }
    }

    private fun mapAllLiteral(mapping: Mapping): LookupElement? {
        val confident = mapping.matches.filter { it.confident }
        if (confident.size < 2) return null
        val values = confident.associate { it.field.name to it.source.text }
        val root = mapping.partner ?: confident.first().source.root
        return item(mapAllText(root), null, AllIcons.Nodes.Template, InsertHandler { ctx, _ ->
            val document = ctx.document
            val offset = ctx.startOffset
            document.deleteString(offset, ctx.tailOffset)
            val manager = PsiDocumentManager.getInstance(ctx.project)
            manager.commitDocument(document)
            val file = ctx.file as? GoFile ?: return@InsertHandler
            val literal = literalAt(file, offset) ?: return@InsertHandler
            val target = GoFillStruct.target(file, literal) ?: return@InsertHandler
            val plan = GoFillStruct.plan(file, target, values.keys, align = true) { values.getValue(it.name) } ?: return@InsertHandler
            val lbrace = target.value.lbrace.textRange.startOffset
            val anchor = document.createRangeMarker(lbrace, lbrace + 1)
            for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
            manager.commitDocument(document)
            val value = file.findElementAt(anchor.startOffset)?.parent as? GoLiteralValue ?: return@InsertHandler
            val last = target.fields.lastOrNull { it.name in values }?.name ?: return@InsertHandler
            val element = value.elements.firstOrNull { keyOf(it) == last } ?: return@InsertHandler
            ctx.editor.caretModel.moveToOffset(element.value?.textRange?.endOffset ?: return@InsertHandler)
        }).withTypeText(confident.joinToString(", ") { it.field.name })
    }

    /**
     * `Name: src.Name` typed in; when the element ends its line the comma Go requires there is added (an existing one is kept). Decided
     * on the text: a literal whose element has no comma before the newline is a syntax error, its PSI has no closing brace to ask.
     */
    private val literalFieldHandler = InsertHandler<LookupElement> { ctx, _ ->
        val document = ctx.document
        val tail = ctx.tailOffset
        val chars = document.charsSequence
        var i = tail
        while (i < chars.length && (chars[i] == ' ' || chars[i] == '\t')) i++
        if (i < chars.length && chars[i] != '\n' && chars[i] != '\r') return@InsertHandler
        document.insertString(tail, ",")
        ctx.editor.caretModel.moveToOffset(tail + 1)
    }

    // --- the assignment block ---

    /** Items on a statement line after `dst.F = src.F` assignments: the fields of `dst` not assigned in that run. */
    fun collectStatements(context: GoCompletionContext, result: CompletionResultSet) {
        if (!enabled(context) || context.kind != GoCompletionContext.Kind.STATEMENT) return
        val reference = context.reference ?: return
        val statement = (reference.parent as? GoLeftHandExprList)?.parent as? GoSimpleStatement ?: return
        val block = statement.parent as? GoBlock ?: return
        val statements = block.statementList
        val index = statements.indexOf(statement)
        if (index <= 0) return
        // the run of `X.F = value` lines right above the caret, nearest first
        val run = ArrayList<Assignment>()
        for (i in index - 1 downTo 0) {
            run += assignmentOf(statements[i]) ?: break
        }
        if (run.isEmpty()) return
        val targetText = run[0].target.text
        val same = run.takeWhile { it.target.text == targetText }
        val targetType = context.semantics.typeOf(same[0].target)
        if (targetType is GoUnknownType) return
        val members = GoMemberCandidates(context)
        val assigned = same.mapTo(HashSet()) { it.field }
        val entries = members.fieldEntries(targetType)
        val fields = entries.filter { it.field.name !in assigned && it.field.name != "_" }.map { it.field }
        if (fields.isEmpty()) return
        val partner = partnerOf(same.map { it.value })
        val targetRoot = rootOf(same[0].target)
        val sources = sources(context, members, reference, targetRoot)
        val mapping = match(fields, sources, partner, top = true, allFields = entries.mapTo(HashSet()) { it.field.name }) ?: return
        for ((i, m) in mapping.matches.withIndex()) {
            val text = "$targetText.${m.field.name} = ${m.source.text}"
            result.addElement(prioritized(item(text, m.field.name, GoIdeIcons.FIELD, null), mapping.top, i + 1))
        }
        val confident = mapping.matches.filter { it.confident }
        if (confident.size < 2) return
        val root = mapping.partner ?: confident.first().source.root
        val lines = confident.map { "$targetText.${it.field.name} = ${it.source.text}" }
        val all = item(mapAllText(root), null, AllIcons.Nodes.Template, InsertHandler { ctx, _ ->
            val document = ctx.document
            val start = ctx.startOffset
            val indent = GoIntentionText.indentAt(document.charsSequence, start)
            val text = lines.joinToString("\n$indent")
            document.replaceString(start, ctx.tailOffset, text)
            ctx.editor.caretModel.moveToOffset(start + text.length)
        }).withTypeText(confident.joinToString(", ") { it.field.name })
        result.addElement(prioritized(all, mapping.top, 0))
    }

    /** `target.field = value` of one statement, or null. */
    class Assignment(val target: GoExpression, val field: String, val value: GoExpression)

    private fun assignmentOf(statement: GoStatement): Assignment? {
        // the `left` rule of the grammar: a block holds the assignment itself, with the left-hand list inside it
        val assignment = statement as? GoAssignmentStatement ?: (statement as? GoSimpleStatement)?.statement as? GoAssignmentStatement ?: return null
        if (assignment.assignOp.text != "=") return null
        val lhs = assignment.leftHandExprList?.expressionList?.singleOrNull() as? GoReferenceExpression ?: return null
        val target = lhs.expression ?: return null
        val field = lhs.identifier?.text ?: return null
        val value = assignment.expressionList.singleOrNull() ?: return null
        return Assignment(target, field, value)
    }

    // --- sources and matching ---

    /** The expressions in scope at [place] with their types: locals, their fields, and the receiver's fields one level deeper. */
    private fun sources(context: GoCompletionContext, members: GoMemberCandidates, place: PsiElement, excludedRoot: String?): List<Source> {
        val semantics = context.semantics
        val out = ArrayList<Source>()
        val roots = GoScopeValues.locals(place).take(MAX_ROOTS)
        for (root in roots) {
            ProgressManager.checkCanceled()
            val name = root.name ?: continue
            if (name == excludedRoot) continue
            val type = semantics.declarationType(root)
            if (type is GoUnknownType) continue
            out += Source(name, type, name)
            addFields(members, name, type, name, out, deeper = root is GoReceiver)
            if (out.size >= MAX_EXPRESSIONS) break
        }
        return out
    }

    private fun addFields(members: GoMemberCandidates, prefix: String, type: GoType, root: String, out: MutableList<Source>, deeper: Boolean) {
        var n = 0
        for (entry in members.fieldEntries(type)) {
            if (n++ >= MAX_FIELDS_PER_ROOT || out.size >= MAX_EXPRESSIONS) return
            val field = entry.field
            if (field.type is GoUnknownType) continue
            val text = "$prefix.${field.name}"
            out += Source(text, field.type, root)
            if (deeper && !field.embedded) addFields(members, text, field.type, root, out, deeper = false)
        }
    }

    /**
     * The best source per field (in declaration order), or null when no field has one worth offering. [allFields] are every field
     * of the target, written or not: a source named like another field of it (`src.ID` for `Age`) is that field's, not this one's.
     */
    fun match(fields: List<GoField>, sources: List<Source>, partner: String?, top: Boolean, allFields: Set<String> = fields.mapTo(HashSet()) { it.name }): Mapping? {
        val matches = ArrayList<Match>()
        for (field in fields) {
            var best: Match? = null
            for (source in sources) {
                if (!GoTypePredicates.assignable(source.type, field.type)) continue
                var score = score(field.name, source, field.type, partner)
                val last = source.text.substringAfterLast('.')
                if (!last.equals(field.name, ignoreCase = true) && allFields.any { it != field.name && it.equals(last, ignoreCase = true) }) score -= OTHER_FIELD_PENALTY
                if (score < OFFER) continue
                if (best == null || score > best.score) best = Match(field, source, score)
            }
            if (best != null) matches += best
        }
        if (matches.isEmpty()) return null
        return Mapping(matches, partner, top && matches.any { it.confident })
    }

    /** 0.6 × name similarity + 0.3 for the partner's expression + 0.1 for an identical type. */
    fun score(fieldName: String, source: Source, fieldType: GoType, partner: String?): Double {
        val sim = similarity(fieldName, source.text.substringAfterLast('.'))
        val fromPartner = partner != null && source.root == partner && source.text != partner
        val identical = GoTypePredicates.identical(source.type, fieldType)
        return 0.6 * sim + (if (fromPartner) 0.3 else 0.0) + (if (identical) 0.1 else 0.0)
    }

    /** 1 equal, 0.9 equal ignoring case, 0.7 one the prefix or suffix of the other (`UserID` ↔ `ID`), else 0.6 × Jaccard of the camel tokens. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.equals(b, ignoreCase = true)) return 0.9
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length >= 2 && (long.startsWith(short, ignoreCase = true) || long.endsWith(short, ignoreCase = true))) return 0.7
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val common = ta.count { it in tb }
        return 0.6 * common / (ta.size + tb.size - common)
    }

    /** `userID` → [user, id], `first_name` → [first, name], `HTTPServer` → [http, server]. */
    fun tokens(name: String): Set<String> {
        val out = HashSet<String>()
        val sb = StringBuilder()
        for ((i, c) in name.withIndex()) {
            if (c == '_' || c == '.') { if (sb.isNotEmpty()) out += sb.toString().lowercase(); sb.clear(); continue }
            if (c.isUpperCase() && sb.isNotEmpty()) {
                val prev = name[i - 1]
                val next = name.getOrNull(i + 1)
                if (prev.isLowerCase() || prev.isDigit() || (prev.isUpperCase() && next != null && next.isLowerCase())) { out += sb.toString().lowercase(); sb.clear() }
            }
            sb.append(c)
        }
        if (sb.isNotEmpty()) out += sb.toString().lowercase()
        return out
    }

    /** The variable most of [values] copy from (`src` of `src.Name`, `src.Profile.Email`), or null when none is a selector chain. */
    fun partnerOf(values: List<GoExpression>): String? {
        val roots = values.mapNotNull { v -> (unwrap(v) as? GoReferenceExpression)?.takeIf { it.expression != null }?.let(::rootOf) }
        return roots.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { -roots.indexOf(it.key) }))?.key
    }

    /** `src` of `src.Profile.Email`; the text of anything else. */
    private fun rootOf(expr: GoExpression): String {
        var e: GoExpression = expr
        while (e is GoReferenceExpression && e.expression != null) e = e.expression!!
        return e.text
    }

    private fun unwrap(expr: GoExpression): GoExpression = when (expr) {
        is GoUnaryExpr -> expr.expression?.let(::unwrap) ?: expr
        is GoParenthesesExpr -> expr.children.filterIsInstance<GoExpression>().firstOrNull()?.let(::unwrap) ?: expr
        else -> expr
    }

    /** The variable [literal] is assigned to (`dst = Dto{…}`): never a source of its own fields. */
    private fun assignedVariable(literal: GoLiteralValue): String? {
        var e: PsiElement? = literal.parent as? GoCompositeLit ?: return null
        while (e is GoUnaryExpr || e is GoParenthesesExpr) e = e.parent
        val list = e?.parent
        val statement = list?.parent
        return when {
            statement is GoAssignmentStatement -> (statement.leftHandExprList.expressionList.singleOrNull() as? GoReferenceExpression)?.takeIf { it.expression == null }?.text
            statement is GoShortVarDeclaration -> statement.varDefinitionList.singleOrNull()?.name
            else -> null
        }
    }

    private fun enabled(context: GoCompletionContext): Boolean =
        GoCompletionAssistSettings.getInstance().mappingEnabled && context.parameters.completionType == CompletionType.BASIC

    private fun literalAt(file: GoFile, offset: Int): GoLiteralValue? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, GoLiteralValue::class.java, false)
    }

    private fun keyOf(e: GoElement): String? = (e.key?.expression as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text

    fun mapAllText(root: String): String = "Map all remaining fields from $root"

    private fun item(text: String, fieldName: String?, icon: javax.swing.Icon, handler: InsertHandler<LookupElement>?): LookupElementBuilder {
        var builder = LookupElementBuilder.create(text).withIcon(icon).withTailText(TAIL, true)
        if (fieldName != null) builder = builder.withLookupStrings(listOf(fieldName))
        if (handler != null) builder = builder.withInsertHandler(handler)
        return builder
    }

    /** At the top (below the Fill items) in a clear mapping context, below every ordinary candidate otherwise; [index] keeps the field order. */
    private fun prioritized(element: LookupElement, top: Boolean, index: Int): LookupElement =
        PrioritizedLookupElement.withPriority(element, if (top) TOP_PRIORITY - index else BOTTOM_PRIORITY - index)
}
