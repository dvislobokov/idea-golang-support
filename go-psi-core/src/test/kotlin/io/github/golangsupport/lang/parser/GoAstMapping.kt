package io.github.golangsupport.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.lexer.GoTokenMapping
import io.github.golangsupport.lang.psi.GoTypes

/** A node of the normalised, go/ast-shaped tree: kind, UTF-8 byte range and a few go/ast fields. */
class AstNode(val kind: String, var start: Int, var end: Int, val extra: String? = null) {
    val children = ArrayList<AstNode>()

    fun count(): Int = 1 + children.sumOf { it.count() }

    override fun toString() = "($kind $start-$end${extra?.let { " $it" } ?: ""})"
}

/**
 * Maps a go-psi PSI tree to the shape of the `go/ast` tree printed by `tools/astdump ast`, so that
 * the two can be diffed ([GorootAstDiffCorpusTest]). Only composite nodes are compared: for every
 * node the go/ast kind and the byte range, plus the cheap go/ast fields `Ident.Name`,
 * `BasicLit.Kind`, operators, `ChanType.Dir`, `SliceExpr.Slice3` and `RangeStmt.Tok`.
 *
 * Normalisations (go-psi deliberately differs from go/ast, see docs/GRAMMAR.md; none of them is a
 * parser bug):
 *
 *  - **Ranges** are trimmed to the first and last significant token: whitespace, comments and
 *    synthetic semicolons are skipped. This removes the doc/leading comments that go-psi binds
 *    into declarations (`DOC_COMMENT_BINDER`) and trailing line breaks. go/ast declaration
 *    positions (`FuncDecl.Pos`, `GenDecl.TokPos`) are never extended by comments either.
 *    PSI char offsets are converted to UTF-8 byte offsets.
 *  - **Wrapper nodes without go/ast counterpart are unwrapped**: `Type`, `Signature`, `SpecType`,
 *    `TypeList`, `Key`, `Value`, `ArgumentList`, `ElseStatement`, `LeftHandExprList`, `ForClause`,
 *    `RangeClause`, `AssignOp`, `CommCase`, `Tag`, `PackageClause`, `ImportList`, and the
 *    `*Definition` nodes (`VarDefinition`, `FieldDefinition`, ... which are just an `Ident`).
 *  - **Leaf identifiers** become `Ident` nodes (go/ast has an `Ident` for every name, go-psi keeps
 *    several of them as bare tokens, for example the name in `FunctionDeclaration`).
 *  - `SimpleStatement` (an expression statement) becomes `ExprStmt`; `ShortVarDeclaration`,
 *    `AssignmentStatement` and a receive with a left-hand side become `AssignStmt`.
 *  - **Synthesised nodes**: `FuncType` for function declarations/literals/method specs (go-psi has
 *    `Signature` and keeps the type parameters beside it), the `BlockStmt` body of switch/select
 *    statements, the `FieldList` bodies of struct/interface types, `Field` for receivers, bare
 *    result types and interface elements, `Ellipsis` for variadic parameters and `[...]T`,
 *    `KeyValueExpr` for keyed elements, `DeclStmt` for declarations inside function bodies,
 *    `BinaryExpr`/`UnaryExpr` chains for constraint elements (`~int | string`),
 *    `AssignStmt`/`ExprStmt` for type switch guards, `IndexExpr`/`IndexListExpr` for a type
 *    followed by type arguments.
 *  - go-psi turns `LiteralValue` into the body of the enclosing `CompositeLit`; a literal value
 *    without a type (elided type in `[]T{{1}}`) becomes a `CompositeLit` of its own.
 *  - `*x` (`UnaryExpr`) and `*T` (`PointerType`) are both `StarExpr`; `(T)` types and
 *    `(x)` expressions are `ParenExpr`; conversions are `CallExpr`.
 *  - go/ast `EmptyStmt` nodes are dropped from the reference tree (see [GoAstDump]); go-psi has no
 *    node for an empty statement.
 */
class GoAstMapping(private val text: String) {

    private val byteOffsets: IntArray = byteOffsets(text)

    fun build(file: PsiFile): AstNode {
        val root = file.node
        val (s, e) = range(root)
        val node = AstNode("File", s, e)
        convChildren(root, node.children)
        return node
    }

    // --- ranges ----------------------------------------------------------------------------------

    private fun isTrivia(t: IElementType) =
        t == TokenType.WHITE_SPACE || t == GoTypes.LINE_COMMENT || t == GoTypes.BLOCK_COMMENT || t == GoTypes.SEMICOLON_SYNTHETIC

    private fun firstSignificant(n: ASTNode): ASTNode? {
        var c = n.firstChildNode
        if (c == null) return if (n !is LeafElement || isTrivia(n.elementType)) null else n
        while (c != null) {
            firstSignificant(c)?.let { return it }
            c = c.treeNext
        }
        return null
    }

    private fun lastSignificant(n: ASTNode): ASTNode? {
        var c = n.lastChildNode
        if (c == null) return if (n !is LeafElement || isTrivia(n.elementType)) null else n
        while (c != null) {
            lastSignificant(c)?.let { return it }
            c = c.treePrev
        }
        return null
    }

    /** Trimmed byte range of [n]. */
    private fun range(n: ASTNode): Pair<Int, Int> {
        val first = firstSignificant(n)
        val last = lastSignificant(n)
        if (first == null || last == null) return byteOffsets[n.startOffset] to byteOffsets[n.startOffset]
        return byteOffsets[first.startOffset] to byteOffsets[last.startOffset + last.textLength]
    }

    private fun start(n: ASTNode) = range(n).first
    private fun end(n: ASTNode) = range(n).second

    // --- helpers ---------------------------------------------------------------------------------

    private fun ASTNode.isLeaf() = firstChildNode == null
    private fun ASTNode.has(type: IElementType) = findChildByType(type) != null
    private fun ASTNode.child(type: IElementType): ASTNode? = findChildByType(type)

    private fun node(kind: String, n: ASTNode, extra: String? = null): AstNode {
        val (s, e) = range(n)
        return AstNode(kind, s, e, extra)
    }

    private fun wrap(kind: String, n: ASTNode, extra: String? = null): AstNode =
        node(kind, n, extra).also { convChildren(n, it.children) }

    private fun tokName(n: ASTNode?): String? = n?.let { GoTokenMapping.goName(it.elementType) }

    private fun ident(leaf: ASTNode): AstNode {
        val (s, e) = range(leaf)
        return AstNode("Ident", s, e, leaf.text)
    }

    // --- conversion ------------------------------------------------------------------------------

    /** Converts every child of [n] into [out]: identifiers become `Ident`, composites go through [conv]. */
    private fun convChildren(n: ASTNode, out: MutableList<AstNode>) {
        var c = n.firstChildNode
        while (c != null) {
            val t = c.elementType
            if (c.isLeaf()) {
                if (t == GoTypes.IDENTIFIER) out += ident(c)
            } else if (t == GoTypes.TYPE_ARGUMENTS) {
                val x = out.removeAt(out.lastIndex)
                val args = ArrayList<AstNode>()
                convChildren(c, args)
                val node = AstNode(if (args.size == 1) "IndexExpr" else "IndexListExpr", x.start, end(c))
                node.children += x
                node.children += args
                out += node
            } else {
                conv(c, out)
            }
            c = c.treeNext
        }
    }

    private fun conv(n: ASTNode, out: MutableList<AstNode>) {
        when (n.elementType) {
            // unwrapped wrappers
            GoTypes.PACKAGE_CLAUSE, GoTypes.IMPORT_LIST, GoTypes.TYPE, GoTypes.SIGNATURE, GoTypes.SPEC_TYPE, GoTypes.TYPE_LIST,
            GoTypes.KEY, GoTypes.VALUE, GoTypes.ARGUMENT_LIST, GoTypes.ELSE_STATEMENT, GoTypes.LEFT_HAND_EXPR_LIST,
            GoTypes.FOR_CLAUSE, GoTypes.RANGE_CLAUSE, GoTypes.ASSIGN_OP, GoTypes.COMM_CASE, GoTypes.TAG, GoTypes.STATEMENT,
            GoTypes.EXPRESSION, GoTypes.SWITCH_STATEMENT, GoTypes.CONST_DEFINITION, GoTypes.VAR_DEFINITION,
            GoTypes.FIELD_DEFINITION, GoTypes.PARAM_DEFINITION, GoTypes.TYPE_PARAM_DEFINITION, GoTypes.LABEL_DEFINITION,
            GoTypes.LABEL_REF, GoTypes.TYPE_ARGUMENTS,
            -> convChildren(n, out)

            // declarations
            GoTypes.IMPORT_DECLARATION -> genDecl(n, "IMPORT", out)
            GoTypes.CONST_DECLARATION -> genDecl(n, "CONST", out)
            GoTypes.VAR_DECLARATION -> genDecl(n, "VAR", out)
            GoTypes.TYPE_DECLARATION -> genDecl(n, "TYPE", out)
            GoTypes.IMPORT_SPEC -> out += importSpec(n)
            GoTypes.CONST_SPEC, GoTypes.VAR_SPEC -> out += wrap("ValueSpec", n)
            GoTypes.TYPE_SPEC -> out += wrap("TypeSpec", n)
            GoTypes.FUNCTION_DECLARATION, GoTypes.METHOD_DECLARATION -> out += funcDecl(n)
            GoTypes.TYPE_PARAMETERS, GoTypes.PARAMETERS -> out += wrap("FieldList", n)
            GoTypes.TYPE_PARAMETER_DECLARATION -> out += wrap("Field", n)
            GoTypes.PARAMETER_DECLARATION -> out += parameterDeclaration(n)
            GoTypes.RECEIVER -> out += receiver(n)
            GoTypes.RESULT -> out += result(n)

            // types
            GoTypes.STRUCT_TYPE -> out += fieldListType("StructType", n)
            GoTypes.INTERFACE_TYPE -> out += fieldListType("InterfaceType", n)
            GoTypes.FIELD_DECLARATION -> out += wrap("Field", n)
            GoTypes.ANONYMOUS_FIELD_DEFINITION ->
                if (n.has(GoTypes.MUL)) out += wrap("StarExpr", n) else convChildren(n, out)
            GoTypes.METHOD_SPEC -> out += methodSpec(n)
            GoTypes.CONSTRAINT_ELEM -> out += constraintElem(n)
            GoTypes.CONSTRAINT_TERM ->
                if (n.has(GoTypes.TILDE)) out += wrap("UnaryExpr", n, "TILDE") else convChildren(n, out)
            GoTypes.ARRAY_OR_SLICE_TYPE -> out += arrayType(n)
            GoTypes.POINTER_TYPE -> out += wrap("StarExpr", n)
            GoTypes.MAP_TYPE -> out += wrap("MapType", n)
            GoTypes.CHANNEL_TYPE -> out += wrap("ChanType", n, chanDir(n))
            GoTypes.FUNCTION_TYPE -> out += functionType(n)
            GoTypes.PAR_TYPE, GoTypes.PARENTHESES_EXPR -> out += wrap("ParenExpr", n)

            // statements
            GoTypes.BLOCK -> out += wrap("BlockStmt", n)
            GoTypes.SIMPLE_STATEMENT ->
                if (n.has(GoTypes.SHORT_VAR_DECLARATION)) convChildren(n, out) else out += wrap("ExprStmt", n)
            GoTypes.SHORT_VAR_DECLARATION -> out += wrap("AssignStmt", n, "DEFINE")
            GoTypes.ASSIGNMENT_STATEMENT -> out += wrap("AssignStmt", n, tokName(firstLeaf(n.child(GoTypes.ASSIGN_OP))))
            GoTypes.SEND_STATEMENT -> out += wrap("SendStmt", n)
            GoTypes.INC_DEC_STATEMENT -> out += wrap("IncDecStmt", n, tokName(n.lastChildNode))
            GoTypes.LABELED_STATEMENT -> out += wrap("LabeledStmt", n)
            GoTypes.GO_STATEMENT -> out += wrap("GoStmt", n)
            GoTypes.DEFER_STATEMENT -> out += wrap("DeferStmt", n)
            GoTypes.RETURN_STATEMENT -> out += wrap("ReturnStmt", n)
            GoTypes.BREAK_STATEMENT -> out += wrap("BranchStmt", n, "BREAK")
            GoTypes.CONTINUE_STATEMENT -> out += wrap("BranchStmt", n, "CONTINUE")
            GoTypes.GOTO_STATEMENT -> out += wrap("BranchStmt", n, "GOTO")
            GoTypes.FALLTHROUGH_STATEMENT -> out += wrap("BranchStmt", n, "FALLTHROUGH")
            GoTypes.IF_STATEMENT -> out += wrap("IfStmt", n)
            GoTypes.FOR_STATEMENT -> out += forStatement(n)
            GoTypes.EXPR_SWITCH_STATEMENT -> out += bodyBlock("SwitchStmt", n)
            GoTypes.TYPE_SWITCH_STATEMENT -> out += bodyBlock("TypeSwitchStmt", n)
            GoTypes.SELECT_STATEMENT -> out += bodyBlock("SelectStmt", n)
            GoTypes.EXPR_CASE_CLAUSE, GoTypes.TYPE_CASE_CLAUSE -> out += wrap("CaseClause", n)
            GoTypes.COMM_CLAUSE -> out += wrap("CommClause", n)
            GoTypes.RECV_STATEMENT ->
                out += if (n.has(GoTypes.DEFINE)) wrap("AssignStmt", n, "DEFINE")
                else if (n.has(GoTypes.ASSIGN)) wrap("AssignStmt", n, "ASSIGN")
                else wrap("ExprStmt", n)
            GoTypes.TYPE_SWITCH_GUARD -> out += typeSwitchGuard(n)

            // expressions
            GoTypes.OR_EXPR, GoTypes.AND_EXPR, GoTypes.CONDITIONAL_EXPR, GoTypes.ADD_EXPR, GoTypes.MUL_EXPR ->
                out += wrap("BinaryExpr", n, tokName(operator(n)))
            GoTypes.UNARY_EXPR ->
                out += if (operator(n)?.elementType == GoTypes.MUL) wrap("StarExpr", n) else wrap("UnaryExpr", n, tokName(operator(n)))
            GoTypes.CALL_EXPR, GoTypes.CONVERSION_EXPR -> out += wrap("CallExpr", n)
            GoTypes.INDEX_OR_SLICE_EXPR -> out += indexOrSlice(n)
            GoTypes.TYPE_ASSERTION_EXPR -> out += wrap("TypeAssertExpr", n)
            GoTypes.REFERENCE_EXPRESSION, GoTypes.TYPE_REFERENCE_EXPRESSION ->
                if (hasCompositeChild(n)) out += wrap("SelectorExpr", n) else convChildren(n, out)
            GoTypes.LITERAL -> out += node("BasicLit", n, tokName(firstLeaf(n)))
            GoTypes.STRING_LITERAL -> out += node("BasicLit", n, "STRING")
            GoTypes.COMPOSITE_LIT -> out += compositeLit(n)
            GoTypes.LITERAL_VALUE -> out += wrap("CompositeLit", n)
            GoTypes.ELEMENT ->
                if (n.has(GoTypes.KEY)) out += wrap("KeyValueExpr", n) else convChildren(n, out)
            GoTypes.FUNCTION_LIT -> out += functionLit(n)

            else -> out += wrap("?${n.elementType}", n) // unmapped: shows up as a mismatch class
        }
    }

    private fun hasCompositeChild(n: ASTNode): Boolean {
        var c = n.firstChildNode
        while (c != null) {
            if (!c.isLeaf()) return true
            c = c.treeNext
        }
        return false
    }

    private fun firstLeaf(n: ASTNode?): ASTNode? {
        var c: ASTNode? = n ?: return null
        while (c != null && !c.isLeaf()) c = c.firstChildNode
        return c
    }

    /** First significant leaf child of [n] (the operator of binary and unary expressions). */
    private fun operator(n: ASTNode): ASTNode? {
        var c = n.firstChildNode
        while (c != null) {
            if (c.isLeaf() && !isTrivia(c.elementType)) return c
            c = c.treeNext
        }
        return null
    }

    // --- declarations ----------------------------------------------------------------------------

    private fun genDecl(n: ASTNode, tok: String, out: MutableList<AstNode>) {
        val decl = wrap("GenDecl", n, tok)
        val parent = n.treeParent
        if (parent != null && (parent.treeParent == null || parent.elementType == GoTypes.IMPORT_LIST)) { // package level
            out += decl
        } else {
            out += AstNode("DeclStmt", decl.start, decl.end).also { it.children += decl }
        }
    }

    private fun importSpec(n: ASTNode): AstNode {
        val spec = node("ImportSpec", n)
        var c = n.firstChildNode
        while (c != null) {
            if (c.isLeaf()) {
                if (c.elementType == GoTypes.IDENTIFIER || c.elementType == GoTypes.PERIOD) spec.children += ident(c)
            } else {
                conv(c, spec.children)
            }
            c = c.treeNext
        }
        return spec
    }

    private fun funcDecl(n: ASTNode): AstNode {
        val decl = node("FuncDecl", n)
        val funcLeaf = n.child(GoTypes.FUNC)!!
        var funcType: AstNode? = null
        var c = n.firstChildNode
        while (c != null) {
            val t = c.elementType
            when {
                c.isLeaf() -> if (t == GoTypes.IDENTIFIER) decl.children += ident(c)
                t == GoTypes.TYPE_PARAMETERS || t == GoTypes.SIGNATURE -> {
                    if (funcType == null) {
                        funcType = AstNode("FuncType", start(funcLeaf), 0)
                        decl.children += funcType
                    }
                    conv(c, funcType.children)
                }
                else -> conv(c, decl.children)
            }
            c = c.treeNext
        }
        funcType?.let { ft -> ft.end = ft.children.maxOf { it.end } }
        return decl
    }

    private fun functionLit(n: ASTNode): AstNode {
        val lit = node("FuncLit", n)
        val sig = n.child(GoTypes.SIGNATURE)!!
        val ft = AstNode("FuncType", start(n.child(GoTypes.FUNC)!!), end(sig))
        convChildren(sig, ft.children)
        lit.children += ft
        n.child(GoTypes.BLOCK)?.let { conv(it, lit.children) }
        return lit
    }

    private fun functionType(n: ASTNode): AstNode {
        val ft = node("FuncType", n)
        n.child(GoTypes.SIGNATURE)?.let { convChildren(it, ft.children) }
        return ft
    }

    private fun parameterDeclaration(n: ASTNode): AstNode {
        val field = node("Field", n)
        var ellipsis: AstNode? = null
        var c = n.firstChildNode
        while (c != null) {
            val target = ellipsis?.children ?: field.children
            if (c.isLeaf()) {
                when (c.elementType) {
                    GoTypes.IDENTIFIER -> target += ident(c)
                    GoTypes.ELLIPSIS -> {
                        ellipsis = AstNode("Ellipsis", start(c), end(n))
                        field.children += ellipsis
                    }
                }
            } else {
                conv(c, target)
            }
            c = c.treeNext
        }
        return field
    }

    private fun receiver(n: ASTNode): AstNode {
        val list = node("FieldList", n)
        val inner = ArrayList<AstNode>()
        convChildren(n, inner)
        val field = AstNode("Field", inner.first().start, inner.maxOf { it.end })
        field.children += inner
        list.children += field
        return list
    }

    private fun result(n: ASTNode): AstNode {
        val params = n.child(GoTypes.PARAMETERS)
        if (params != null) return wrap("FieldList", params)
        val list = node("FieldList", n)
        val field = node("Field", n)
        convChildren(n, field.children)
        list.children += field
        return list
    }

    private fun methodSpec(n: ASTNode): AstNode {
        val field = node("Field", n)
        var funcType: AstNode? = null
        var c = n.firstChildNode
        while (c != null) {
            val t = c.elementType
            when {
                c.isLeaf() -> if (t == GoTypes.IDENTIFIER) field.children += ident(c)
                t == GoTypes.TYPE_PARAMETERS || t == GoTypes.SIGNATURE -> {
                    if (funcType == null) {
                        funcType = AstNode("FuncType", 0, 0)
                        field.children += funcType
                    }
                    conv(c, funcType.children)
                }
                else -> conv(c, field.children)
            }
            c = c.treeNext
        }
        funcType?.let { ft ->
            // go/ast: FuncType.Pos() is the position of the parameter list when there is no `func` keyword.
            val params = n.child(GoTypes.SIGNATURE)?.child(GoTypes.PARAMETERS)
            ft.start = if (params != null) start(params) else ft.children.first().start
            ft.end = ft.children.maxOf { it.end }
        }
        return field
    }

    private fun constraintElem(n: ASTNode): AstNode {
        // Left-associative chain of `|`, as go/parser builds it.
        val terms = ArrayList<AstNode>()
        var c = n.firstChildNode
        while (c != null) {
            if (!c.isLeaf()) conv(c, terms)
            c = c.treeNext
        }
        var acc = terms.first()
        for (i in 1 until terms.size) {
            acc = AstNode("BinaryExpr", acc.start, terms[i].end, "OR").also { it.children += acc; it.children += terms[i] }
        }
        return acc
    }

    private fun fieldListType(kind: String, n: ASTNode): AstNode {
        val type = node(kind, n)
        val lbrace = n.child(GoTypes.LBRACE)
        val rbrace = n.lastChildNode.let { var x = it; while (x != null && x.elementType != GoTypes.RBRACE) x = x.treePrev; x }
        val list = AstNode("FieldList", if (lbrace != null) start(lbrace) else type.start, if (rbrace != null) end(rbrace) else type.end)
        var c = n.firstChildNode
        while (c != null) {
            if (!c.isLeaf()) {
                if (kind == "InterfaceType" && c.elementType == GoTypes.CONSTRAINT_ELEM) {
                    val f = node("Field", c)
                    conv(c, f.children)
                    list.children += f
                } else {
                    conv(c, list.children)
                }
            }
            c = c.treeNext
        }
        type.children += list
        return type
    }

    private fun arrayType(n: ASTNode): AstNode {
        val type = node("ArrayType", n)
        var c = n.firstChildNode
        while (c != null) {
            if (c.isLeaf()) {
                if (c.elementType == GoTypes.ELLIPSIS) type.children += AstNode("Ellipsis", start(c), end(c))
                else if (c.elementType == GoTypes.IDENTIFIER) type.children += ident(c)
            } else {
                conv(c, type.children)
            }
            c = c.treeNext
        }
        return type
    }

    private fun chanDir(n: ASTNode): String {
        var seenChan = false
        var c = n.firstChildNode
        while (c != null) {
            when (c.elementType) {
                GoTypes.CHAN -> seenChan = true
                GoTypes.ARROW -> return if (seenChan) "dir=1" else "dir=2"
            }
            c = c.treeNext
        }
        return "dir=3"
    }

    // --- statements ------------------------------------------------------------------------------

    private fun forStatement(n: ASTNode): AstNode {
        val range = n.child(GoTypes.RANGE_CLAUSE)
        if (range == null) return wrap("ForStmt", n)
        val tok = when {
            range.has(GoTypes.DEFINE) -> "DEFINE"
            range.has(GoTypes.ASSIGN) -> "ASSIGN"
            else -> null
        }
        return wrap("RangeStmt", n, tok)
    }

    /** switch/select: the statement, plus a `BlockStmt` for the braces holding the clauses. */
    private fun bodyBlock(kind: String, n: ASTNode): AstNode {
        val stmt = node(kind, n)
        var rbrace: ASTNode? = n.lastChildNode
        while (rbrace != null && rbrace.elementType != GoTypes.RBRACE) rbrace = rbrace.treePrev
        var body: AstNode? = null
        var c = n.firstChildNode
        while (c != null) {
            if (c.isLeaf()) {
                if (c.elementType == GoTypes.LBRACE && body == null) {
                    body = AstNode("BlockStmt", start(c), if (rbrace != null) end(rbrace) else end(n))
                    stmt.children += body
                } else if (c.elementType == GoTypes.IDENTIFIER) {
                    (body?.children ?: stmt.children) += ident(c)
                }
            } else {
                conv(c, body?.children ?: stmt.children)
            }
            c = c.treeNext
        }
        return stmt
    }

    private fun typeSwitchGuard(n: ASTNode): AstNode {
        val variable = n.child(GoTypes.VAR_DEFINITION)
        val inner = ArrayList<AstNode>()
        var c = n.firstChildNode
        var seenDefine = false
        while (c != null) {
            if (c.elementType == GoTypes.DEFINE) seenDefine = true
            if (!c.isLeaf() && c.elementType != GoTypes.VAR_DEFINITION) conv(c, inner)
            c = c.treeNext
        }
        val x = inner.first()
        val ta = AstNode("TypeAssertExpr", x.start, end(n)).also { it.children += inner }
        if (variable == null || !seenDefine) {
            return AstNode("ExprStmt", ta.start, ta.end).also { it.children += ta }
        }
        return AstNode("AssignStmt", start(variable), end(n), "DEFINE").also {
            convChildren(variable, it.children)
            it.children += ta
        }
    }

    private fun indexOrSlice(n: ASTNode): AstNode {
        val isSlice = n.has(GoTypes.COLON)
        val parts = ArrayList<AstNode>()
        convChildren(n, parts)
        if (isSlice) {
            var colons = 0
            var c = n.firstChildNode
            while (c != null) {
                if (c.elementType == GoTypes.COLON) colons++
                c = c.treeNext
            }
            return node("SliceExpr", n, if (colons == 2) "3" else null).also { it.children += parts }
        }
        return node(if (parts.size > 2) "IndexListExpr" else "IndexExpr", n).also { it.children += parts }
    }

    private fun compositeLit(n: ASTNode): AstNode {
        val lit = node("CompositeLit", n)
        var bracketArgs: ArrayList<AstNode>? = null
        var c = n.firstChildNode
        while (c != null) {
            val t = c.elementType
            if (c.isLeaf()) {
                // `T[A, B]{...}`: the type arguments are inlined between bare brackets (TypeArgumentsNoPin).
                if (t == GoTypes.IDENTIFIER) {
                    (bracketArgs ?: lit.children) += ident(c)
                } else if (t == GoTypes.LBRACK && bracketArgs == null) {
                    bracketArgs = ArrayList()
                } else if (t == GoTypes.RBRACK && bracketArgs != null) {
                    val x = lit.children.removeAt(lit.children.lastIndex)
                    lit.children += AstNode(if (bracketArgs.size == 1) "IndexExpr" else "IndexListExpr", x.start, end(c)).also {
                        it.children += x
                        it.children += bracketArgs!!
                    }
                    bracketArgs = null
                }
            } else if (t == GoTypes.LITERAL_VALUE) {
                convChildren(c, lit.children)
            } else {
                conv(c, bracketArgs ?: lit.children)
            }
            c = c.treeNext
        }
        return lit
    }

    companion object {
        /** UTF-8 byte offset of every char index (and of text.length). */
        fun byteOffsets(text: String): IntArray {
            val result = IntArray(text.length + 1)
            var bytes = 0
            var i = 0
            while (i < text.length) {
                result[i] = bytes
                val c = text[i]
                if (c.code < 0x80) {
                    bytes += 1
                } else if (c.code < 0x800) {
                    bytes += 2
                } else if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    result[i + 1] = bytes
                    bytes += 4
                    i++
                } else {
                    bytes += 3
                }
                i++
            }
            result[text.length] = bytes
            return result
        }
    }
}
