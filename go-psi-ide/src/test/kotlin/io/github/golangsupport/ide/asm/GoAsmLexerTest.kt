package io.github.golangsupport.ide.asm

import com.intellij.psi.TokenType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tokens of the Go assembly lexer: symbols with `·` and `∕`, immediates, pseudo-registers, ABI suffixes, labels, preprocessor lines. */
class GoAsmLexerTest {

    private fun tokens(text: String, skipSpace: Boolean = true): List<String> {
        val lexer = GoAsmLexer()
        lexer.start(text)
        val out = mutableListOf<String>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (!skipSpace || type !== TokenType.WHITE_SPACE) out += "$type '${text.substring(lexer.tokenStart, lexer.tokenEnd)}'"
            lexer.advance()
        }
        return out
    }

    @Test
    fun textDirectiveWithAbiSuffix() = assertEquals(
        listOf(
            "DIRECTIVE 'TEXT'", "SYMBOL '·add'", "ABI_SUFFIX '<ABIInternal>'", "( '('", "PSEUDO_REGISTER 'SB'", ") ')'", ", ','",
            "IDENTIFIER 'NOSPLIT'", "OPERATOR '|'", "IDENTIFIER 'NOFRAME'", ", ','", "NUMBER '\$0'", "OPERATOR '-'", "NUMBER '24'",
        ),
        tokens("TEXT ·add<ABIInternal>(SB), NOSPLIT|NOFRAME, \$0-24"),
    )

    @Test
    fun operandsRegistersAndImmediates() = assertEquals(
        listOf(
            "INSTRUCTION 'MOVQ'", "IDENTIFIER 'a'", "OPERATOR '+'", "NUMBER '0'", "( '('", "PSEUDO_REGISTER 'FP'", ") ')'", ", ','",
            "REGISTER 'AX'", "INSTRUCTION 'ADDQ'", "NUMBER '\$0x10'", ", ','", "REGISTER 'AX'", "INSTRUCTION 'MOVQ'", "NUMBER '\$-8'", ", ','",
            "REGISTER 'R8'", "LINE_COMMENT '// done'",
        ),
        tokens("\tMOVQ a+0(FP), AX\n\tADDQ \$0x10, AX\n\tMOVQ \$-8, R8 // done\n"),
    )

    @Test
    fun labelsSymbolsAndPackagePaths() = assertEquals(
        listOf(
            "LABEL 'loop'", ": ':'", "INSTRUCTION 'CALL'", "SYMBOL 'runtime·morestack'", "( '('", "PSEUDO_REGISTER 'SB'", ") ')'",
            "INSTRUCTION 'JMP'", "SYMBOL 'internal∕bytealg·IndexString'", "( '('", "PSEUDO_REGISTER 'SB'", ") ')'",
            "INSTRUCTION 'JMP'", "IDENTIFIER 'loop'", "; ';'", "INSTRUCTION 'RET'",
        ),
        tokens("loop:\tCALL runtime·morestack(SB)\n\tJMP internal∕bytealg·IndexString(SB)\n\tJMP loop; RET"),
    )

    @Test
    fun preprocessorStringsAndComments() = assertEquals(
        listOf(
            "PREPROCESSOR '#include'", "STRING '\"textflag.h\"'", "PREPROCESSOR '#define'", "IDENTIFIER 'Big'", "NUMBER '0x4330000000000000'",
            "LINE_COMMENT '// 2**52'", "BLOCK_COMMENT '/* func add(a, b int) int */'", "DIRECTIVE 'DATA'", "SYMBOL '·msg'", "OPERATOR '+'",
            "NUMBER '0'", "( '('", "PSEUDO_REGISTER 'SB'", ") ')'", "OPERATOR '/'", "NUMBER '8'", ", ','", "OPERATOR '\$'", "STRING '\"hi\\n\"'",
            "DIRECTIVE 'GLOBL'", "SYMBOL '·tab'", "ABI_SUFFIX '<>'", "( '('", "PSEUDO_REGISTER 'SB'", ") ')'",
        ),
        tokens(
            "#include \"textflag.h\"\n#define Big 0x4330000000000000 // 2**52\n/* func add(a, b int) int */\n" +
                "DATA ·msg+0(SB)/8, \$\"hi\\n\"\nGLOBL ·tab<>(SB)",
        ),
    )

    @Test
    fun shiftsAndBadCharacter() = assertEquals(
        listOf("INSTRUCTION 'MOVQ'", "OPERATOR '\$'", "OPERATOR '~'", "( '('", "NUMBER '1'", "OPERATOR '<<'", "NUMBER '63'", ") ')'",
            "BAD_CHARACTER '¤'"),
        tokens("MOVQ \$~(1<<63) ¤"),
    )

    /** The platform restarts relexing at a token of state 0: every line start must be one, a label keeps it. */
    @Test
    fun stateIsStatementStartAtLineStarts() {
        val text = "TEXT ·f(SB),0,\$0\nl:\tRET\n"
        val lexer = GoAsmLexer()
        lexer.start(text)
        val states = mutableListOf<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType !== TokenType.WHITE_SPACE) states += "${text.substring(lexer.tokenStart, lexer.tokenEnd)}=${lexer.state}"
            lexer.advance()
        }
        assertEquals(listOf("TEXT=0", "·f=1", "(=1", "SB=1", ")=1", ",=1", "0=1", ",=1", "\$0=1", "l=0", ":=0", "RET=0"), states)
    }

    @Test
    fun parsesSymbolNames() {
        assertEquals(GoAsmName("", "add", 1), GoAsmName.parse("·add"))
        assertEquals(GoAsmName("runtime", "morestack", 8), GoAsmName.parse("runtime·morestack"))
        assertEquals(GoAsmName("internal/bytealg", "Index", 17), GoAsmName.parse("internal∕bytealg·Index"))
        assertEquals(null, GoAsmName.parse("·"))
        assertEquals(null, GoAsmName.parse("main"))
        assertEquals(true, GoAsmName.parse("internal∕bytealg·Index")!!.inPackage("bytealg"))
        assertEquals(false, GoAsmName.parse("runtime·x")!!.inPackage("bytealg"))
    }
}
