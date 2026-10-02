package io.github.golangsupport.ide.formatter.printer

import com.intellij.psi.tree.IElementType

/**
 * A minimal mirror of `go/ast`, built from PSI by [GoAstBuilder] and consumed by [GoPrinter].
 *
 * Positions are character offsets into the file text (`NO_POS` when absent). Only the positions
 * that `go/printer` reads are stored: tokens themselves are taken from the source token stream in
 * order, so punctuation (commas, semicolons) needs no fields.
 */
internal const val NO_POS = -1

internal sealed class GoNode {
    /** Offset of the first token. */
    abstract val pos: Int

    /** Offset just past the last token. */
    abstract val end: Int
}

// --- Expressions --------------------------------------------------------------------------------

internal sealed class GoExpr : GoNode()

internal class GoIdent(override val pos: Int, override val end: Int) : GoExpr()

/** INT, FLOAT, IMAG, CHAR, STRING (raw strings included); [kind] is the token type. */
internal class GoBasicLit(override val pos: Int, override val end: Int, val kind: IElementType) : GoExpr()

internal class GoCompositeLit(val type: GoExpr?, val lbrace: Int, val elts: List<GoExpr>, val rbrace: Int) : GoExpr() {
    override val pos get() = type?.pos ?: lbrace
    override val end get() = rbrace + 1
}

internal class GoFuncLit(val type: GoFuncType, val body: GoBlockStmt) : GoExpr() {
    override val pos get() = type.pos
    override val end get() = body.end
}

internal class GoParenExpr(val lparen: Int, val x: GoExpr, val rparen: Int) : GoExpr() {
    override val pos get() = lparen
    override val end get() = rparen + 1
}

internal class GoSelectorExpr(val x: GoExpr, val sel: GoIdent) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = sel.end
}

internal class GoIndexExpr(val x: GoExpr, val lbrack: Int, val index: GoExpr, val rbrack: Int) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = rbrack + 1
}

internal class GoIndexListExpr(val x: GoExpr, val lbrack: Int, val indices: List<GoExpr>, val rbrack: Int) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = rbrack + 1
}

internal class GoSliceExpr(
    val x: GoExpr, val lbrack: Int, val low: GoExpr?, val high: GoExpr?, val max: GoExpr?, val slice3: Boolean, val rbrack: Int,
) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = rbrack + 1
}

/** `x.(T)`; [type] is null for `x.(type)` in a type switch guard. */
internal class GoTypeAssertExpr(val x: GoExpr, val lparen: Int, val type: GoExpr?, val rparen: Int) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = rparen + 1
}

internal class GoCallExpr(val fn: GoExpr, val lparen: Int, val args: List<GoExpr>, val ellipsis: Int, val rparen: Int) : GoExpr() {
    override val pos get() = fn.pos
    override val end get() = rparen + 1
}

internal class GoStarExpr(val star: Int, val x: GoExpr) : GoExpr() {
    override val pos get() = star
    override val end get() = x.end
}

internal class GoUnaryExpr(val opPos: Int, val op: IElementType, val x: GoExpr) : GoExpr() {
    override val pos get() = opPos
    override val end get() = x.end
}

internal class GoBinaryExpr(val x: GoExpr, val opPos: Int, val op: IElementType, val y: GoExpr) : GoExpr() {
    override val pos get() = x.pos
    override val end get() = y.end
}

internal class GoKeyValueExpr(val key: GoExpr, val colon: Int, val value: GoExpr) : GoExpr() {
    override val pos get() = key.pos
    override val end get() = value.end
}

/** `[len]elt` or `[]elt`; `[...]elt` has an [GoEllipsis] length. */
internal class GoArrayType(val lbrack: Int, val len: GoExpr?, val elt: GoExpr) : GoExpr() {
    override val pos get() = lbrack
    override val end get() = elt.end
}

internal class GoStructType(val structPos: Int, val fields: GoFieldList) : GoExpr() {
    override val pos get() = structPos
    override val end get() = fields.end
}

/** [funcPos] is `NO_POS` for method specs and function declarations' signatures. */
internal class GoFuncType(
    val funcPos: Int, val typeParams: GoFieldList?, val params: GoFieldList, val results: GoFieldList?,
) : GoExpr() {
    override val pos get() = if (funcPos != NO_POS) funcPos else typeParams?.pos ?: params.pos
    override val end get() = results?.end ?: params.end
}

internal class GoInterfaceType(val interfacePos: Int, val methods: GoFieldList) : GoExpr() {
    override val pos get() = interfacePos
    override val end get() = methods.end
}

internal class GoMapType(val mapPos: Int, val key: GoExpr, val value: GoExpr) : GoExpr() {
    override val pos get() = mapPos
    override val end get() = value.end
}

internal enum class ChanDir { SEND, RECV, BOTH }

internal class GoChanType(val begin: Int, val arrow: Int, val dir: ChanDir, val value: GoExpr) : GoExpr() {
    override val pos get() = begin
    override val end get() = value.end
}

internal class GoEllipsis(val ellipsis: Int, val elt: GoExpr?) : GoExpr() {
    override val pos get() = ellipsis
    override val end get() = elt?.end ?: (ellipsis + 3)
}

// --- Fields -------------------------------------------------------------------------------------

/** [opening]/[closing] are `NO_POS` for an unparenthesised single result type. */
internal class GoFieldList(val opening: Int, val list: List<GoField>, val closing: Int) : GoNode() {
    override val pos get() = if (opening != NO_POS) opening else list.firstOrNull()?.pos ?: NO_POS
    override val end get() = if (closing != NO_POS) closing + 1 else list.lastOrNull()?.end ?: NO_POS

    fun numFields(): Int = list.sumOf { if (it.names.isEmpty()) 1 else it.names.size }
}

/** [hasLineComment] mirrors `ast.Field.Comment != nil`. */
internal class GoField(val names: List<GoIdent>, val type: GoExpr, val tag: GoBasicLit?, val hasLineComment: Boolean) : GoNode() {
    override val pos get() = names.firstOrNull()?.pos ?: type.pos
    override val end get() = tag?.end ?: type.end
}

// --- Statements ---------------------------------------------------------------------------------

internal sealed class GoStmt : GoNode()

internal class GoDeclStmt(val decl: GoGenDecl) : GoStmt() {
    override val pos get() = decl.pos
    override val end get() = decl.end
}

internal class GoEmptyStmt(override val pos: Int) : GoStmt() {
    override val end get() = pos
}

internal class GoLabeledStmt(val label: GoIdent, val colon: Int, val stmt: GoStmt) : GoStmt() {
    override val pos get() = label.pos
    override val end get() = if (stmt is GoEmptyStmt) colon + 1 else stmt.end
}

internal class GoExprStmt(val x: GoExpr) : GoStmt() {
    override val pos get() = x.pos
    override val end get() = x.end
}

internal class GoSendStmt(val chan: GoExpr, val arrow: Int, val value: GoExpr) : GoStmt() {
    override val pos get() = chan.pos
    override val end get() = value.end
}

internal class GoIncDecStmt(val x: GoExpr, val tokPos: Int, val tok: IElementType) : GoStmt() {
    override val pos get() = x.pos
    override val end get() = tokPos + 2
}

internal class GoAssignStmt(val lhs: List<GoExpr>, val tokPos: Int, val tok: IElementType, val rhs: List<GoExpr>) : GoStmt() {
    override val pos get() = lhs.first().pos
    override val end get() = rhs.last().end
}

internal class GoGoStmt(val goPos: Int, val call: GoExpr) : GoStmt() {
    override val pos get() = goPos
    override val end get() = call.end
}

internal class GoDeferStmt(val deferPos: Int, val call: GoExpr) : GoStmt() {
    override val pos get() = deferPos
    override val end get() = call.end
}

internal class GoReturnStmt(val returnPos: Int, val results: List<GoExpr>) : GoStmt() {
    override val pos get() = returnPos
    override val end get() = results.lastOrNull()?.end ?: (returnPos + 6)
}

internal class GoBranchStmt(val tokPos: Int, val tokEnd: Int, val label: GoIdent?) : GoStmt() {
    override val pos get() = tokPos
    override val end get() = label?.end ?: tokEnd
}

internal class GoBlockStmt(val lbrace: Int, val list: List<GoStmt>, val rbrace: Int) : GoStmt() {
    override val pos get() = lbrace
    override val end get() = rbrace + 1
}

internal class GoIfStmt(val ifPos: Int, val init: GoStmt?, val cond: GoExpr, val body: GoBlockStmt, val els: GoStmt?) : GoStmt() {
    override val pos get() = ifPos
    override val end get() = els?.end ?: body.end
}

/** A switch case clause; [list] is null for `default`. */
internal class GoCaseClause(val casePos: Int, val list: List<GoExpr>?, val colon: Int, val body: List<GoStmt>) : GoStmt() {
    override val pos get() = casePos
    override val end get() = body.lastOrNull()?.end ?: (colon + 1)
}

internal class GoSwitchStmt(val switchPos: Int, val init: GoStmt?, val tag: GoExpr?, val body: GoBlockStmt) : GoStmt() {
    override val pos get() = switchPos
    override val end get() = body.end
}

internal class GoTypeSwitchStmt(val switchPos: Int, val init: GoStmt?, val assign: GoStmt, val body: GoBlockStmt) : GoStmt() {
    override val pos get() = switchPos
    override val end get() = body.end
}

/** A select clause; [comm] is null for `default`. */
internal class GoCommClause(val casePos: Int, val comm: GoStmt?, val colon: Int, val body: List<GoStmt>) : GoStmt() {
    override val pos get() = casePos
    override val end get() = body.lastOrNull()?.end ?: (colon + 1)
}

internal class GoSelectStmt(val selectPos: Int, val body: GoBlockStmt) : GoStmt() {
    override val pos get() = selectPos
    override val end get() = body.end
}

internal class GoForStmt(val forPos: Int, val init: GoStmt?, val cond: GoExpr?, val post: GoStmt?, val body: GoBlockStmt) : GoStmt() {
    override val pos get() = forPos
    override val end get() = body.end
}

/** [tok] is null for `for range x`. */
internal class GoRangeStmt(
    val forPos: Int, val key: GoExpr?, val value: GoExpr?, val tokPos: Int, val tok: IElementType?, val x: GoExpr, val body: GoBlockStmt,
) : GoStmt() {
    override val pos get() = forPos
    override val end get() = body.end
}

// --- Declarations -------------------------------------------------------------------------------

internal sealed class GoSpec : GoNode()

internal class GoImportSpec(val name: GoIdent?, val path: GoBasicLit) : GoSpec() {
    override val pos get() = name?.pos ?: path.pos
    override val end get() = path.end
}

internal class GoValueSpec(val names: List<GoIdent>, val type: GoExpr?, val values: List<GoExpr>?, val hasLineComment: Boolean) : GoSpec() {
    override val pos get() = names.first().pos
    override val end get() = values?.last()?.end ?: type?.end ?: names.last().end
}

internal class GoTypeSpec(val name: GoIdent, val typeParams: GoFieldList?, val assign: Int, val type: GoExpr) : GoSpec() {
    override val pos get() = name.pos
    override val end get() = type.end
}

internal sealed class GoDecl : GoNode()

/** [tok] is IMPORT, CONST, TYPE_ or VAR. */
internal class GoGenDecl(val tokPos: Int, val tok: IElementType, val lparen: Int, val specs: List<GoSpec>, val rparen: Int) : GoDecl() {
    override val pos get() = tokPos
    override val end get() = if (rparen != NO_POS) rparen + 1 else specs.lastOrNull()?.end ?: (tokPos + 1)
}

internal class GoFuncDecl(val funcPos: Int, val recv: GoFieldList?, val name: GoIdent, val type: GoFuncType, val body: GoBlockStmt?) : GoDecl() {
    override val pos get() = funcPos
    override val end get() = body?.end ?: type.end
}

internal class GoFileNode(val packagePos: Int, val name: GoIdent, val decls: List<GoDecl>) : GoNode() {
    override val pos get() = packagePos
    override val end get() = decls.lastOrNull()?.end ?: name.end
}
