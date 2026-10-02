// Go lexer: all tokens of the Go specification (https://go.dev/ref/spec#Lexical_elements) with
// automatic semicolon insertion (https://go.dev/ref/spec#Semicolons). The behaviour mirrors
// go/scanner (src/go/scanner/scanner.go) so that token kinds and offsets diff 1:1 against
// tools/astdump; known deviations are listed in testData/lexer/corpus-allowlist.txt.
//
// Malformed literals are lexed permissively, like go/scanner does: errors are reported by later
// phases, never by BAD_CHARACTER.
package io.github.golangsupport.lang.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;

import static io.github.golangsupport.lang.psi.GoTypes.*;

%%

%public
%class _GoLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

// Entered after a token that allows a semicolon to be inserted at the end of the line
// (go/scanner's insertSemi). A newline in this state becomes SEMICOLON_SYNTHETIC.
%state MAYBE_SEMICOLON

%{
  public _GoLexer() {
    this((java.io.Reader) null);
  }

  /** Returns the type and enters MAYBE_SEMICOLON: a following newline becomes a semicolon. */
  private IElementType semi(IElementType type) {
    yybegin(MAYBE_SEMICOLON);
    return type;
  }

  /** Returns the type and leaves MAYBE_SEMICOLON. */
  private IElementType plain(IElementType type) {
    yybegin(YYINITIAL);
    return type;
  }

  /** Classifies a numeric literal the way go/scanner.scanNumber does. */
  private IElementType number() {
    CharSequence text = yytext();
    int length = text.length();
    if (text.charAt(length - 1) == 'i') return IMAG;
    boolean hex = length > 1 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X');
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (c == '.' || c == 'p' || c == 'P' || (!hex && (c == 'e' || c == 'E'))) return FLOAT;
    }
    return INT;
  }
%}

// go/scanner ends a line comment at '\n' only; a '\r' before it belongs to the comment.
LINE_COMMENT = "//" [^\n]*
// A terminated block comment, or an unterminated one running to the end of input.
BLOCK_COMMENT = "/*" !([^]* "*/" [^]*) ("*/")?

LETTER = [\p{L}_]
DIGIT = [\p{Nd}]
IDENT = {LETTER} ({LETTER} | {DIGIT})*

// Numbers: digit groups accept any decimal digit and '_' in every base, like go/scanner.digits;
// invalid digits and misplaced separators are errors for later phases.
DEC_DIGITS = [0-9_]*
HEX_DIGITS = [0-9a-fA-F_]*
// Hexadecimal mantissas only take a 'p' exponent: 'e' is a hex digit.
HEX_NUMBER = 0 [xX] {HEX_DIGITS} ("." {HEX_DIGITS})? ([pP] [+-]? {DEC_DIGITS})? "i"?
OTHER_MANTISSA = 0 [oObB] {DEC_DIGITS} ("." {DEC_DIGITS})?
               | [0-9] {DEC_DIGITS} ("." {DEC_DIGITS})?
               | "." [0-9] {DEC_DIGITS}
OTHER_NUMBER = {OTHER_MANTISSA} ([eEpP] [+-]? {DEC_DIGITS})? "i"?

// Rune and interpreted string literals stop before a newline when unterminated. An escape
// consumes the backslash and the next character; a lone backslash right before a newline or EOF
// is part of the unterminated literal.
CHAR_LITERAL = "'" ([^'\\\n] | \\ [^\n])* ("'" | \\)?
STRING_LITERAL = \" ([^\"\\\n] | \\ [^\n])* (\" | \\)?
RAW_STRING_LITERAL = "`" [^`]* "`"?

%%

<MAYBE_SEMICOLON> {
  // go/scanner reports the inserted semicolon at the newline with the literal "\n". A preceding
  // '\r' is ordinary whitespace (go/scanner.skipWhitespace), so offsets match on CRLF files too.
  "\n"             { return plain(SEMICOLON_SYNTHETIC); }
  [ \t\r]+         { return TokenType.WHITE_SPACE; }
  // Comments keep the state, so the newline after them still inserts the semicolon. go/scanner
  // reports the semicolon of a multi-line block comment at its first inner newline; a token
  // cannot be split, so here it is inserted only at the next newline after the comment.
  {LINE_COMMENT}   { return LINE_COMMENT; }
  {BLOCK_COMMENT}  { return BLOCK_COMMENT; }
}

<YYINITIAL> {
  [ \t\r\n]+       { return TokenType.WHITE_SPACE; }
  {LINE_COMMENT}   { return LINE_COMMENT; }
  {BLOCK_COMMENT}  { return BLOCK_COMMENT; }
  // A byte order mark is ignored only as the very first character (go/scanner.Init).
  ﻿           { return zzStartRead == 0 ? TokenType.WHITE_SPACE : TokenType.BAD_CHARACTER; }
}

<YYINITIAL, MAYBE_SEMICOLON> {
  // Keywords (before IDENT: equal-length matches go to the earlier rule)
  "break"          { return semi(BREAK); }
  "case"           { return plain(CASE); }
  "chan"           { return plain(CHAN); }
  "const"          { return plain(CONST); }
  "continue"       { return semi(CONTINUE); }
  "default"        { return plain(DEFAULT); }
  "defer"          { return plain(DEFER); }
  "else"           { return plain(ELSE); }
  "fallthrough"    { return semi(FALLTHROUGH); }
  "for"            { return plain(FOR); }
  "func"           { return plain(FUNC); }
  "go"             { return plain(GO); }
  "goto"           { return plain(GOTO); }
  "if"             { return plain(IF); }
  "import"         { return plain(IMPORT); }
  "interface"      { return plain(INTERFACE); }
  "map"            { return plain(MAP); }
  "package"        { return plain(PACKAGE); }
  "range"          { return plain(RANGE); }
  "return"         { return semi(RETURN); }
  "select"         { return plain(SELECT); }
  "struct"         { return plain(STRUCT); }
  "switch"         { return plain(SWITCH); }
  "type"           { return plain(TYPE_); }
  "var"            { return plain(VAR); }

  {IDENT}          { return semi(IDENTIFIER); }

  {HEX_NUMBER}     { return semi(number()); }
  {OTHER_NUMBER}   { return semi(number()); }
  {CHAR_LITERAL}   { return semi(CHAR); }
  {STRING_LITERAL} { return semi(STRING); }
  {RAW_STRING_LITERAL} { return semi(RAW_STRING); }

  // Operators and punctuation; longest match picks '&^=' over '&^' over '&', '<-' over '<', etc.
  "&^="            { return plain(AND_NOT_ASSIGN); }
  "<<="            { return plain(SHL_ASSIGN); }
  ">>="            { return plain(SHR_ASSIGN); }
  "..."            { return plain(ELLIPSIS); }
  "+="             { return plain(ADD_ASSIGN); }
  "-="             { return plain(SUB_ASSIGN); }
  "*="             { return plain(MUL_ASSIGN); }
  "/="             { return plain(QUO_ASSIGN); }
  "%="             { return plain(REM_ASSIGN); }
  "&="             { return plain(AND_ASSIGN); }
  "|="             { return plain(OR_ASSIGN); }
  "^="             { return plain(XOR_ASSIGN); }
  "&^"             { return plain(AND_NOT); }
  "<<"             { return plain(SHL); }
  ">>"             { return plain(SHR); }
  "&&"             { return plain(LAND); }
  "||"             { return plain(LOR); }
  "<-"             { return plain(ARROW); }
  "++"             { return semi(INC); }
  "--"             { return semi(DEC); }
  "=="             { return plain(EQL); }
  "!="             { return plain(NEQ); }
  "<="             { return plain(LEQ); }
  ">="             { return plain(GEQ); }
  ":="             { return plain(DEFINE); }
  "+"              { return plain(ADD); }
  "-"              { return plain(SUB); }
  "*"              { return plain(MUL); }
  "/"              { return plain(QUO); }
  "%"              { return plain(REM); }
  "&"              { return plain(AND); }
  "|"              { return plain(OR); }
  "^"              { return plain(XOR); }
  "<"              { return plain(LSS); }
  ">"              { return plain(GTR); }
  "="              { return plain(ASSIGN); }
  "!"              { return plain(NOT); }
  "("              { return plain(LPAREN); }
  "["              { return plain(LBRACK); }
  "{"              { return plain(LBRACE); }
  ","              { return plain(COMMA); }
  "."              { return plain(PERIOD); }
  ")"              { return semi(RPAREN); }
  "]"              { return semi(RBRACK); }
  "}"              { return semi(RBRACE); }
  ";"              { return plain(SEMICOLON); }
  ":"              { return plain(COLON); }
  "~"              { return plain(TILDE); }
}

// Anything else (for example '\f', curly quotes, '#', '$', '?', '@', '\\'). The state is kept,
// like go/scanner keeps insertSemi for ILLEGAL tokens.
[^]                { return TokenType.BAD_CHARACTER; }
