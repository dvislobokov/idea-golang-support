package io.github.golangsupport.ide.formatter.printer

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTypes.*

/**
 * Converts the Go PSI of a file into the [GoAst] mirror of `go/ast`. Any shape it does not know
 * (syntax errors, unsupported constructs) raises [GoPrinterMismatch].
 */
internal class GoAstBuilder(private val source: GoSource) {

    fun file(root: ASTNode): GoFileNode {
        val children = sig(root)
        var i = 0
        val clause = children.getOrNull(i++)?.takeIf { it.elementType == PACKAGE_CLAUSE } ?: fail(root, "package clause")
        val decls = ArrayList<GoDecl>()
        while (i < children.size) {
            val c = children[i++]
            if (c.elementType != SEMICOLON) decls += topLevelDecls(c)
        }
        return fileOf(clause, decls)
    }

    /** The file node for a package clause followed by [decls]. */
    fun fileOf(clause: ASTNode, decls: List<GoDecl>): GoFileNode {
        val clauseKids = sig(clause)
        val packagePos = (clauseKids.firstOrNull { it.elementType == PACKAGE } ?: fail(clause, "package")).startOffset
        val name = ident(clauseKids.firstOrNull { it.elementType == IDENTIFIER } ?: fail(clause, "package name"))
        return GoFileNode(packagePos, name, decls)
    }

    /** The declarations of a top-level node (an import list holds several). */
    fun topLevelDecls(c: ASTNode): List<GoDecl> = when (c.elementType) {
        IMPORT_LIST -> sig(c).map { genDecl(it) }
        IMPORT_DECLARATION, CONST_DECLARATION, VAR_DECLARATION, TYPE_DECLARATION -> listOf(genDecl(c))
        FUNCTION_DECLARATION, METHOD_DECLARATION -> listOf(funcDecl(c))
        else -> fail(c, "top-level declaration")
    }

    // --- Declarations ---------------------------------------------------------------------------

    private fun genDecl(node: ASTNode): GoGenDecl {
        val kids = sig(node)
        val keyword = kids.first()
        val tok = keyword.elementType
        val lparen = kids.firstOrNull { it.elementType == LPAREN }?.startOffset ?: NO_POS
        val rparen = if (lparen != NO_POS) kids.lastOrNull { it.elementType == RPAREN }?.startOffset ?: fail(node, "')'") else NO_POS
        val specs = kids.filter { it.elementType in SPEC_TYPES }.map { spec(it, tok) }
        return GoGenDecl(keyword.startOffset, tok, lparen, specs, rparen)
    }

    private fun spec(node: ASTNode, tok: IElementType): GoSpec {
        val kids = sig(node)
        return when (node.elementType) {
            IMPORT_SPEC -> {
                val path = kids.last()
                val name = if (kids.size > 1) ident(kids.first()) else null
                GoImportSpec(name, basicLit(path))
            }
            CONST_SPEC, VAR_SPEC -> {
                val names = kids.filter { it.elementType == CONST_DEFINITION || it.elementType == VAR_DEFINITION }.map { ident(it) }
                val assign = kids.indexOfFirst { it.elementType == ASSIGN }
                val afterNames = kids.indexOfLast { it.elementType == CONST_DEFINITION || it.elementType == VAR_DEFINITION } + 1
                val typeEnd = if (assign >= 0) assign else kids.size
                val type = if (typeEnd > afterNames) type(kids[afterNames]) else null
                val values = if (assign >= 0) exprList(kids.subList(assign + 1, kids.size)) else null
                GoValueSpec(names, type, values, hasLineComment(node))
            }
            TYPE_SPEC -> {
                val name = ident(kids[0])
                var i = 1
                var typeParams: GoFieldList? = null
                if (kids.getOrNull(i)?.elementType == TYPE_PARAMETERS) typeParams = typeParameters(kids[i++])
                var assign = NO_POS
                if (kids.getOrNull(i)?.elementType == ASSIGN) assign = kids[i++].startOffset
                val specType = kids.getOrNull(i) ?: fail(node, "type")
                GoTypeSpec(name, typeParams, assign, type(specType))
            }
            else -> fail(node, "spec of $tok")
        }
    }

    private fun funcDecl(node: ASTNode): GoFuncDecl {
        val kids = sig(node)
        var i = 0
        val funcPos = kids[i++].startOffset
        var recv: GoFieldList? = null
        if (kids.getOrNull(i)?.elementType == RECEIVER) recv = receiver(kids[i++])
        val name = ident(kids.getOrNull(i++) ?: fail(node, "name"))
        var typeParams: GoFieldList? = null
        if (kids.getOrNull(i)?.elementType == TYPE_PARAMETERS) typeParams = typeParameters(kids[i++])
        val signature = kids.getOrNull(i++)?.takeIf { it.elementType == SIGNATURE } ?: fail(node, "signature")
        val type = signature(NO_POS, typeParams, signature)
        val body = kids.getOrNull(i)?.let { block(it) }
        return GoFuncDecl(funcPos, recv, name, type, body)
    }

    private fun receiver(node: ASTNode): GoFieldList {
        val kids = sig(node)
        val lparen = kids.first().startOffset
        val rparen = kids.last().also { expect(it, RPAREN) }.startOffset
        val inner = kids.subList(1, kids.size - 1).filter { it.elementType != COMMA }
        val field = when (inner.size) {
            1 -> GoField(emptyList(), type(inner[0]), null, false)
            2 -> GoField(listOf(ident(inner[0])), type(inner[1]), null, false)
            else -> fail(node, "receiver")
        }
        return GoFieldList(lparen, listOf(field), rparen)
    }

    private fun signature(funcPos: Int, typeParams: GoFieldList?, node: ASTNode): GoFuncType {
        val kids = sig(node)
        val params = parameters(kids.first())
        val results = kids.getOrNull(1)?.let { result(it) }
        return GoFuncType(funcPos, typeParams, params, results)
    }

    private fun result(node: ASTNode): GoFieldList {
        val kids = sig(node)
        val only = kids.singleOrNull() ?: fail(node, "result")
        if (only.elementType == PARAMETERS) return parameters(only)
        return GoFieldList(NO_POS, listOf(GoField(emptyList(), type(only), null, false)), NO_POS)
    }

    private fun parameters(node: ASTNode): GoFieldList {
        expect(node, PARAMETERS)
        val kids = sig(node)
        val lparen = kids.first().startOffset
        val rparen = kids.last().also { expect(it, RPAREN) }.startOffset
        val fields = kids.filter { it.elementType == PARAMETER_DECLARATION }.map { parameterDeclaration(it) }
        return GoFieldList(lparen, fields, rparen)
    }

    private fun parameterDeclaration(node: ASTNode): GoField {
        val kids = sig(node)
        val names = kids.filter { it.elementType == PARAM_DEFINITION }.map { ident(it) }
        val ellipsis = kids.firstOrNull { it.elementType == ELLIPSIS }
        val typeNode = kids.last()
        val t = type(typeNode)
        val fieldType = if (ellipsis != null) GoEllipsis(ellipsis.startOffset, t) else t
        return GoField(names, fieldType, null, false)
    }

    private fun typeParameters(node: ASTNode): GoFieldList {
        val kids = sig(node)
        val lbrack = kids.first().startOffset
        val rbrack = kids.last().also { expect(it, RBRACK) }.startOffset
        val fields = kids.filter { it.elementType == TYPE_PARAMETER_DECLARATION }.map { decl ->
            val dk = sig(decl)
            val names = dk.filter { it.elementType == TYPE_PARAM_DEFINITION }.map { ident(it) }
            GoField(names, constraintElem(dk.last()), null, false)
        }
        return GoFieldList(lbrack, fields, rbrack)
    }

    private fun constraintElem(node: ASTNode): GoExpr {
        if (node.elementType != CONSTRAINT_ELEM) return type(node)
        val kids = sig(node)
        var result = constraintTerm(kids[0])
        var i = 1
        while (i + 1 < kids.size) {
            val op = kids[i]
            result = GoBinaryExpr(result, op.startOffset, op.elementType, constraintTerm(kids[i + 1]))
            i += 2
        }
        return result
    }

    private fun constraintTerm(node: ASTNode): GoExpr {
        if (node.elementType != CONSTRAINT_TERM) return type(node)
        val kids = sig(node)
        return if (kids.size == 2 && kids[0].elementType == TILDE) {
            GoUnaryExpr(kids[0].startOffset, TILDE, type(kids[1]))
        } else {
            type(kids.single())
        }
    }

    // --- Types ----------------------------------------------------------------------------------

    fun type(node: ASTNode): GoExpr {
        val kids = sig(node)
        return when (node.elementType) {
            TYPE, SPEC_TYPE -> {
                if (node.elementType == SPEC_TYPE) return type(kids.single())
                val base = kids.first()
                val x = when (base.elementType) {
                    TYPE_REFERENCE_EXPRESSION -> typeReference(base)
                    else -> type(base)
                }
                val args = kids.getOrNull(1)
                if (args == null) x else typeArguments(x, args)
            }
            TYPE_REFERENCE_EXPRESSION -> typeReference(node)
            PAR_TYPE -> GoParenExpr(kids.first().startOffset, type(kids[1]), kids.last().also { expect(it, RPAREN) }.startOffset)
            ARRAY_OR_SLICE_TYPE -> {
                val lbrack = kids.first().startOffset
                val rb = kids.indexOfFirst { it.elementType == RBRACK }
                val len = when (rb) {
                    1 -> null
                    2 -> if (kids[1].elementType == ELLIPSIS) GoEllipsis(kids[1].startOffset, null) else expr(kids[1])
                    else -> fail(node, "array length")
                }
                GoArrayType(lbrack, len, type(kids[rb + 1]))
            }
            POINTER_TYPE -> GoStarExpr(kids.first().startOffset, type(kids[1]))
            FUNCTION_TYPE -> signature(kids.first().startOffset, null, kids[1])
            MAP_TYPE -> GoMapType(kids.first().startOffset, type(kids[2]), type(kids[4]))
            CHANNEL_TYPE -> {
                val first = kids[0]
                when {
                    first.elementType == ARROW -> GoChanType(first.startOffset, first.startOffset, ChanDir.RECV, type(kids[2]))
                    kids[1].elementType == ARROW -> GoChanType(first.startOffset, kids[1].startOffset, ChanDir.SEND, type(kids[2]))
                    else -> GoChanType(first.startOffset, NO_POS, ChanDir.BOTH, type(kids[1]))
                }
            }
            STRUCT_TYPE -> GoStructType(kids.first().startOffset, structFields(node, kids))
            INTERFACE_TYPE -> GoInterfaceType(kids.first().startOffset, interfaceElements(node, kids))
            TYPE_LIST -> fail(node, "type list in type position")
            else -> expr(node)
        }
    }

    private fun typeReference(node: ASTNode): GoExpr {
        val kids = sig(node)
        return if (kids.size == 1) {
            ident(kids[0])
        } else {
            GoSelectorExpr(expr(kids[0]), ident(kids[2]))
        }
    }

    private fun typeArguments(x: GoExpr, node: ASTNode): GoExpr {
        expect(node, TYPE_ARGUMENTS)
        val kids = sig(node)
        val lbrack = kids.first().startOffset
        val rbrack = kids.last().also { expect(it, RBRACK) }.startOffset
        val args = kids.subList(1, kids.size - 1).filter { it.elementType != COMMA }.map { type(it) }
        return if (args.size == 1 && kids.size == 3) GoIndexExpr(x, lbrack, args[0], rbrack) else GoIndexListExpr(x, lbrack, args, rbrack)
    }

    private fun structFields(node: ASTNode, kids: List<ASTNode>): GoFieldList {
        val lbrace = kids.first { it.elementType == LBRACE }.startOffset
        val rbrace = kids.lastOrNull { it.elementType == RBRACE }?.startOffset ?: fail(node, "'}'")
        val fields = kids.filter { it.elementType == FIELD_DECLARATION }.map { f ->
            val fk = sig(f)
            val tag = fk.lastOrNull()?.takeIf { it.elementType == TAG }?.let { basicLit(sig(it).single()) }
            val body = if (tag != null) fk.dropLast(1) else fk
            if (body.size == 1 && body[0].elementType == ANONYMOUS_FIELD_DEFINITION) {
                GoField(emptyList(), anonymousField(body[0]), tag, hasLineComment(f))
            } else {
                val names = body.filter { it.elementType == FIELD_DEFINITION }.map { ident(it) }
                GoField(names, type(body.last()), tag, hasLineComment(f))
            }
        }
        return GoFieldList(lbrace, fields, rbrace)
    }

    private fun anonymousField(node: ASTNode): GoExpr {
        val kids = sig(node)
        var i = 0
        val star = if (kids[0].elementType == MUL) kids[i++].startOffset else NO_POS
        var t = typeReference(kids[i++])
        kids.getOrNull(i)?.let { t = typeArguments(t, it) }
        return if (star != NO_POS) GoStarExpr(star, t) else t
    }

    private fun interfaceElements(node: ASTNode, kids: List<ASTNode>): GoFieldList {
        val lbrace = kids.first { it.elementType == LBRACE }.startOffset
        val rbrace = kids.lastOrNull { it.elementType == RBRACE }?.startOffset ?: fail(node, "'}'")
        val fields = kids.filter { it.elementType == METHOD_SPEC || it.elementType == CONSTRAINT_ELEM }.map { e ->
            if (e.elementType == METHOD_SPEC) {
                val mk = sig(e)
                val name = ident(mk[0])
                var i = 1
                var typeParams: GoFieldList? = null
                if (mk.getOrNull(i)?.elementType == TYPE_PARAMETERS) typeParams = typeParameters(mk[i++])
                GoField(listOf(name), signature(NO_POS, typeParams, mk[i]), null, hasLineComment(e))
            } else {
                GoField(emptyList(), constraintElem(e), null, hasLineComment(e))
            }
        }
        return GoFieldList(lbrace, fields, rbrace)
    }

    // --- Expressions ----------------------------------------------------------------------------

    fun expr(node: ASTNode): GoExpr {
        val kids = sig(node)
        return when (node.elementType) {
            REFERENCE_EXPRESSION -> if (kids.size == 1) ident(kids[0]) else GoSelectorExpr(expr(kids[0]), ident(kids[2]))
            LITERAL -> basicLit(kids.single())
            STRING_LITERAL -> basicLit(kids.single())
            OR_EXPR, AND_EXPR, CONDITIONAL_EXPR, ADD_EXPR, MUL_EXPR -> {
                if (kids.size != 3) fail(node, "binary expression")
                GoBinaryExpr(expr(kids[0]), kids[1].startOffset, kids[1].elementType, expr(kids[2]))
            }
            UNARY_EXPR -> {
                val op = kids[0]
                if (op.elementType == MUL) GoStarExpr(op.startOffset, expr(kids[1])) else GoUnaryExpr(op.startOffset, op.elementType, expr(kids[1]))
            }
            PARENTHESES_EXPR -> GoParenExpr(kids.first().startOffset, expr(kids[1]), kids.last().also { expect(it, RPAREN) }.startOffset)
            CALL_EXPR -> {
                val fn = expr(kids[0])
                val args = kids[1].also { expect(it, ARGUMENT_LIST) }
                val ak = sig(args)
                val lparen = ak.first().startOffset
                val rparen = ak.last().also { expect(it, RPAREN) }.startOffset
                val inner = ak.subList(1, ak.size - 1)
                val ellipsis = inner.firstOrNull { it.elementType == ELLIPSIS }?.startOffset ?: NO_POS
                val list = inner.filter { it.elementType != COMMA && it.elementType != ELLIPSIS }.map { expr(it) }
                GoCallExpr(fn, lparen, list, ellipsis, rparen)
            }
            CONVERSION_EXPR -> {
                val fn = type(kids[0])
                val lparen = kids[1].also { expect(it, LPAREN) }.startOffset
                val rparen = kids.last().also { expect(it, RPAREN) }.startOffset
                GoCallExpr(fn, lparen, listOf(expr(kids[2])), NO_POS, rparen)
            }
            INDEX_OR_SLICE_EXPR -> indexOrSlice(node, kids)
            TYPE_ASSERTION_EXPR -> {
                val lparen = kids.first { it.elementType == LPAREN }
                GoTypeAssertExpr(expr(kids[0]), lparen.startOffset, type(kids[3]), kids.last().also { expect(it, RPAREN) }.startOffset)
            }
            FUNCTION_LIT -> GoFuncLit(signature(kids[0].startOffset, null, kids[1]), block(kids[2]))
            COMPOSITE_LIT -> {
                var t = type(kids[0])
                if (kids.size > 2) {
                    // unpinned type arguments (`T[int]{...}`) are flattened into the literal
                    val lbrack = kids[1].also { expect(it, LBRACK) }.startOffset
                    val rbrack = kids[kids.size - 2].also { expect(it, RBRACK) }.startOffset
                    val inner = kids.subList(2, kids.size - 2)
                    val args = inner.filter { it.elementType != COMMA }.map { type(it) }
                    t = if (args.size == 1 && inner.size == 1) GoIndexExpr(t, lbrack, args[0], rbrack) else GoIndexListExpr(t, lbrack, args, rbrack)
                }
                literalValue(t, kids.last())
            }
            LITERAL_VALUE -> literalValue(null, node)
            TYPE, TYPE_REFERENCE_EXPRESSION, PAR_TYPE, ARRAY_OR_SLICE_TYPE, POINTER_TYPE, FUNCTION_TYPE, MAP_TYPE, CHANNEL_TYPE,
            STRUCT_TYPE, INTERFACE_TYPE -> type(node)
            KEY, VALUE -> expr(kids.single())
            else -> fail(node, "expression")
        }
    }

    private fun indexOrSlice(node: ASTNode, kids: List<ASTNode>): GoExpr {
        val x = expr(kids[0])
        val lbrack = kids[1].also { expect(it, LBRACK) }.startOffset
        val rbrack = kids.last().also { expect(it, RBRACK) }.startOffset
        val inner = kids.subList(2, kids.size - 1)
        val colons = inner.count { it.elementType == COLON }
        if (colons > 0) {
            val parts = arrayOfNulls<GoExpr>(3)
            var slot = 0
            for (k in inner) {
                if (k.elementType == COLON) slot++ else parts[slot] = expr(k)
            }
            return GoSliceExpr(x, lbrack, parts[0], parts[1], parts[2], colons == 2, rbrack)
        }
        val items = inner.filter { it.elementType != COMMA }.map { expr(it) }
        if (items.isEmpty()) fail(node, "index")
        return if (items.size == 1 && inner.size == 1) GoIndexExpr(x, lbrack, items[0], rbrack) else GoIndexListExpr(x, lbrack, items, rbrack)
    }

    private fun literalValue(type: GoExpr?, node: ASTNode): GoCompositeLit {
        expect(node, LITERAL_VALUE)
        val kids = sig(node)
        val lbrace = kids.first().startOffset
        val rbrace = kids.last().also { expect(it, RBRACE) }.startOffset
        val elts = kids.filter { it.elementType == ELEMENT }.map { e ->
            val ek = sig(e)
            if (ek.size == 3 && ek[1].elementType == COLON) {
                GoKeyValueExpr(expr(ek[0]), ek[1].startOffset, expr(ek[2]))
            } else {
                expr(ek.single())
            }
        }
        return GoCompositeLit(type, lbrace, elts, rbrace)
    }

    private fun exprList(nodes: List<ASTNode>): List<GoExpr> = nodes.filter { it.elementType != COMMA }.map { expr(it) }

    // --- Statements -----------------------------------------------------------------------------

    fun block(node: ASTNode): GoBlockStmt {
        expect(node, BLOCK)
        val kids = sig(node)
        val lbrace = kids.first().startOffset
        val rbrace = kids.last().also { expect(it, RBRACE) }.startOffset
        return GoBlockStmt(lbrace, statements(kids.subList(1, kids.size - 1)), rbrace)
    }

    private fun statements(nodes: List<ASTNode>): List<GoStmt> = nodes.filter { it.elementType != SEMICOLON }.map { stmt(it) }

    private fun stmt(node: ASTNode): GoStmt {
        val kids = sig(node)
        return when (node.elementType) {
            CONST_DECLARATION, VAR_DECLARATION, TYPE_DECLARATION -> GoDeclStmt(genDecl(node))
            SIMPLE_STATEMENT, STATEMENT, SWITCH_STATEMENT -> stmt(kids.singleOrNull() ?: fail(node, "statement"))
            LEFT_HAND_EXPR_LIST -> GoExprStmt(expr(kids.singleOrNull() ?: fail(node, "expression statement")))
            SHORT_VAR_DECLARATION -> {
                val def = kids.indexOfFirst { it.elementType == DEFINE }
                GoAssignStmt(
                    kids.subList(0, def).filter { it.elementType != COMMA }.map { ident(it) },
                    kids[def].startOffset, DEFINE, exprList(kids.subList(def + 1, kids.size)),
                )
            }
            ASSIGNMENT_STATEMENT -> {
                val op = kids[1].also { expect(it, ASSIGN_OP) }
                val opLeaf = sig(op).single()
                GoAssignStmt(lhs(kids[0]), opLeaf.startOffset, opLeaf.elementType, exprList(kids.subList(2, kids.size)))
            }
            SEND_STATEMENT -> GoSendStmt(lhs(kids[0]).single(), kids[1].startOffset, expr(kids[2]))
            INC_DEC_STATEMENT -> GoIncDecStmt(lhs(kids[0]).single(), kids[1].startOffset, kids[1].elementType)
            LABELED_STATEMENT -> {
                val label = ident(kids[0])
                val colon = kids[1].startOffset
                val inner = kids.getOrNull(2)?.let { stmt(it) } ?: GoEmptyStmt(colon + 1)
                GoLabeledStmt(label, colon, inner)
            }
            GO_STATEMENT -> GoGoStmt(kids[0].startOffset, expr(kids[1]))
            DEFER_STATEMENT -> GoDeferStmt(kids[0].startOffset, expr(kids[1]))
            RETURN_STATEMENT -> GoReturnStmt(kids[0].startOffset, exprList(kids.drop(1)))
            BREAK_STATEMENT, CONTINUE_STATEMENT, GOTO_STATEMENT, FALLTHROUGH_STATEMENT ->
                GoBranchStmt(kids[0].startOffset, kids[0].startOffset + kids[0].textLength, kids.getOrNull(1)?.let { ident(it) })
            BLOCK -> block(node)
            IF_STATEMENT -> ifStmt(node, kids)
            EXPR_SWITCH_STATEMENT -> {
                val lb = kids.indexOfFirst { it.elementType == LBRACE }
                val (init, tag) = header(kids.subList(1, lb))
                GoSwitchStmt(kids[0].startOffset, init, tag?.let { exprOf(it) }, caseBody(kids, lb))
            }
            TYPE_SWITCH_STATEMENT -> {
                val lb = kids.indexOfFirst { it.elementType == LBRACE }
                val (init, guard) = header(kids.subList(1, lb))
                GoTypeSwitchStmt(kids[0].startOffset, init, typeSwitchGuard(guard ?: fail(node, "guard")), caseBody(kids, lb))
            }
            SELECT_STATEMENT -> GoSelectStmt(kids[0].startOffset, caseBody(kids, 1))
            FOR_STATEMENT -> forStmt(node, kids)
            else -> fail(node, "statement")
        }
    }

    private fun lhs(node: ASTNode): List<GoExpr> {
        expect(node, LEFT_HAND_EXPR_LIST)
        return exprList(sig(node))
    }

    /** `[init ';'] x` of if/switch headers: returns (init, x) where x may be null. */
    private fun header(nodes: List<ASTNode>): Pair<GoStmt?, ASTNode?> {
        val semi = nodes.indexOfFirst { it.elementType == SEMICOLON }
        if (semi < 0) return null to nodes.singleOrNull()
        val init = nodes.subList(0, semi).singleOrNull()?.let { stmt(it) }
        return init to nodes.subList(semi + 1, nodes.size).singleOrNull()
    }

    private fun exprOf(node: ASTNode): GoExpr = expr(node)

    private fun typeSwitchGuard(node: ASTNode): GoStmt {
        if (node.elementType != TYPE_SWITCH_GUARD) return stmt(node)
        val kids = sig(node)
        val def = kids.indexOfFirst { it.elementType == DEFINE }
        val start = if (def >= 0) def + 1 else 0
        val x = expr(kids[start])
        val lparen = kids[start + 2].also { expect(it, LPAREN) }.startOffset
        val rparen = kids.last().also { expect(it, RPAREN) }.startOffset
        val assert = GoTypeAssertExpr(x, lparen, null, rparen)
        return if (def >= 0) GoAssignStmt(listOf(ident(kids[0])), kids[def].startOffset, DEFINE, listOf(assert)) else GoExprStmt(assert)
    }

    private fun caseBody(kids: List<ASTNode>, lb: Int): GoBlockStmt {
        val lbrace = kids[lb].also { expect(it, LBRACE) }.startOffset
        val rbrace = kids.last().also { expect(it, RBRACE) }.startOffset
        val clauses = kids.subList(lb + 1, kids.size - 1).map { clause(it) }
        return GoBlockStmt(lbrace, clauses, rbrace)
    }

    private fun clause(node: ASTNode): GoStmt {
        val kids = sig(node)
        val colon = kids.indexOfFirst { it.elementType == COLON }
        if (colon < 0) fail(node, "':'")
        val body = statements(kids.subList(colon + 1, kids.size))
        val casePos = kids[0].startOffset
        return when (node.elementType) {
            EXPR_CASE_CLAUSE, TYPE_CASE_CLAUSE -> {
                val list = if (kids[0].elementType == DEFAULT) {
                    null
                } else {
                    kids.subList(1, colon).flatMap { if (it.elementType == TYPE_LIST) sig(it) else listOf(it) }
                        .filter { it.elementType != COMMA }.map { expr(it) }
                }
                GoCaseClause(casePos, list, kids[colon].startOffset, body)
            }
            COMM_CLAUSE -> {
                val comm = kids[0].also { expect(it, COMM_CASE) }
                val ck = sig(comm)
                val stmt = if (ck[0].elementType == DEFAULT) null else commStmt(ck[1])
                GoCommClause(casePos, stmt, kids[colon].startOffset, body)
            }
            else -> fail(node, "case clause")
        }
    }

    private fun commStmt(node: ASTNode): GoStmt {
        if (node.elementType != RECV_STATEMENT) return stmt(node)
        val kids = sig(node)
        val define = kids.indexOfFirst { it.elementType == DEFINE }
        val assign = kids.indexOfFirst { it.elementType == ASSIGN }
        return when {
            define >= 0 -> GoAssignStmt(kids.subList(0, define).filter { it.elementType != COMMA }.map { ident(it) }, kids[define].startOffset, DEFINE, listOf(expr(kids.last())))
            assign >= 0 -> GoAssignStmt(lhs(kids[0]), kids[assign].startOffset, ASSIGN, listOf(expr(kids.last())))
            else -> GoExprStmt(expr(kids.single()))
        }
    }

    private fun ifStmt(node: ASTNode, kids: List<ASTNode>): GoIfStmt {
        val blockIdx = kids.indexOfFirst { it.elementType == BLOCK }
        val (init, cond) = header(kids.subList(1, blockIdx))
        val body = block(kids[blockIdx])
        val els = kids.getOrNull(blockIdx + 1)?.let { e ->
            expect(e, ELSE_STATEMENT)
            val ek = sig(e)
            stmt(ek[1])
        }
        return GoIfStmt(kids[0].startOffset, init, expr(cond ?: fail(node, "condition")), body, els)
    }

    private fun forStmt(node: ASTNode, kids: List<ASTNode>): GoStmt {
        val forPos = kids[0].startOffset
        val body = block(kids.last())
        val header = kids.subList(1, kids.size - 1)
        if (header.isEmpty()) return GoForStmt(forPos, null, null, null, body)
        val h = header.single()
        return when (h.elementType) {
            RANGE_CLAUSE -> {
                val rk = sig(h)
                val range = rk.indexOfFirst { it.elementType == RANGE }
                val x = expr(rk[range + 1])
                if (range == 0) return GoRangeStmt(forPos, null, null, NO_POS, null, x, body)
                val tokNode = rk[range - 1]
                val lhsNodes = rk.subList(0, range - 1)
                val lhs = if (lhsNodes.size == 1 && lhsNodes[0].elementType == LEFT_HAND_EXPR_LIST) lhs(lhsNodes[0])
                else lhsNodes.filter { it.elementType != COMMA }.map { ident(it) }
                GoRangeStmt(forPos, lhs[0], lhs.getOrNull(1), tokNode.startOffset, tokNode.elementType, x, body)
            }
            FOR_CLAUSE -> {
                val fk = sig(h)
                val semis = fk.withIndex().filter { it.value.elementType == SEMICOLON }.map { it.index }
                if (semis.size != 2) fail(h, "for clause")
                val init = fk.subList(0, semis[0]).singleOrNull()?.let { stmt(it) }
                val cond = fk.subList(semis[0] + 1, semis[1]).singleOrNull()?.let { expr(it) }
                val post = fk.subList(semis[1] + 1, fk.size).singleOrNull()?.let { stmt(it) }
                GoForStmt(forPos, init, cond, post, body)
            }
            else -> GoForStmt(forPos, null, expr(h), null, body)
        }
    }

    // --- Leaves ---------------------------------------------------------------------------------

    private fun ident(node: ASTNode): GoIdent {
        val leaf = leafOf(node)
        return GoIdent(leaf.startOffset, leaf.startOffset + leaf.textLength)
    }

    private fun basicLit(node: ASTNode): GoBasicLit {
        val leaf = leafOf(node)
        val kind = when (leaf.elementType) {
            RAW_STRING -> STRING
            INT, FLOAT, IMAG, CHAR, STRING -> leaf.elementType
            else -> fail(leaf, "literal")
        }
        return GoBasicLit(leaf.startOffset, leaf.startOffset + leaf.textLength, kind)
    }

    private fun leafOf(node: ASTNode): ASTNode {
        var n = node
        while (n.firstChildNode != null) n = sig(n).singleOrNull() ?: fail(node, "single token")
        return n
    }

    /**
     * `go/parser` line comment of a field or spec: a comment group starting on the line where the
     * node ends, after which the next token is on another line, a semicolon, or EOF.
     */
    private fun hasLineComment(node: ASTNode): Boolean {
        val last = source.lastTokenBefore(node.startOffset + node.textLength) ?: return false
        val leaves = source.leaves
        var line = source.lineFor(last.end - 1)
        var c = leaves.getOrNull(last.index + 1) ?: return false
        if (!c.isComment || source.lineFor(c.offset) != line) return false
        // consume the group: comments starting on the line where the previous one ended
        while (true) {
            line = source.lineFor(c.end - 1)
            val next = leaves.getOrNull(c.index + 1) ?: return true
            if (next.isComment && source.lineFor(next.offset) <= line) {
                c = next
                continue
            }
            if (next.isComment) return true
            // an inserted semicolon between the comment and the token counts as SEMICOLON
            if (hasSyntheticBetween(c.end, next.offset)) return true
            return next.type == SEMICOLON || source.lineFor(next.offset) != line
        }
    }

    private fun hasSyntheticBetween(from: Int, to: Int): Boolean = source.hasSyntheticSemicolonIn(from, to)

    private fun expect(node: ASTNode, type: IElementType) {
        if (node.elementType != type) fail(node, type.toString())
    }

    companion object {
        private val SPEC_TYPES = TokenSet.create(IMPORT_SPEC, CONST_SPEC, VAR_SPEC, TYPE_SPEC)

        /** Significant children: no whitespace, comments, inserted semicolons or empty nodes; error elements are flattened. */
        fun sig(node: ASTNode): List<ASTNode> {
            val result = ArrayList<ASTNode>()
            var c = node.firstChildNode
            while (c != null) {
                val type = c.elementType
                when {
                    c.textLength == 0 -> {}
                    GoSource.isTrivia(type) -> {}
                    type == TokenType.ERROR_ELEMENT -> result += sig(c)
                    else -> result += c
                }
                c = c.treeNext
            }
            return result
        }

        fun fail(node: ASTNode, what: String): Nothing =
            throw GoPrinterMismatch("unexpected ${node.elementType} at ${node.startOffset} (expected $what)")
    }
}
