package main

import (
	"bytes"
	"fmt"
	"go/scanner"
	"go/token"
	"strconv"
	"strings"
)

var operatorNames = map[token.Token]string{
	token.ADD: "ADD", token.SUB: "SUB", token.MUL: "MUL", token.QUO: "QUO", token.REM: "REM",
	token.AND: "AND", token.OR: "OR", token.XOR: "XOR", token.SHL: "SHL", token.SHR: "SHR",
	token.AND_NOT: "AND_NOT", token.ADD_ASSIGN: "ADD_ASSIGN", token.SUB_ASSIGN: "SUB_ASSIGN",
	token.MUL_ASSIGN: "MUL_ASSIGN", token.QUO_ASSIGN: "QUO_ASSIGN", token.REM_ASSIGN: "REM_ASSIGN",
	token.AND_ASSIGN: "AND_ASSIGN", token.OR_ASSIGN: "OR_ASSIGN", token.XOR_ASSIGN: "XOR_ASSIGN",
	token.SHL_ASSIGN: "SHL_ASSIGN", token.SHR_ASSIGN: "SHR_ASSIGN", token.AND_NOT_ASSIGN: "AND_NOT_ASSIGN",
	token.LAND: "LAND", token.LOR: "LOR", token.ARROW: "ARROW", token.INC: "INC", token.DEC: "DEC",
	token.EQL: "EQL", token.LSS: "LSS", token.GTR: "GTR", token.ASSIGN: "ASSIGN", token.NOT: "NOT",
	token.NEQ: "NEQ", token.LEQ: "LEQ", token.GEQ: "GEQ", token.DEFINE: "DEFINE", token.ELLIPSIS: "ELLIPSIS",
	token.LPAREN: "LPAREN", token.LBRACK: "LBRACK", token.LBRACE: "LBRACE", token.COMMA: "COMMA",
	token.PERIOD: "PERIOD", token.RPAREN: "RPAREN", token.RBRACK: "RBRACK", token.RBRACE: "RBRACE",
	token.SEMICOLON: "SEMICOLON", token.COLON: "COLON", token.TILDE: "TILDE",
}

// tokenName returns the go/token constant name (ADD, LPAREN, FUNC, IDENT, ...).
func tokenName(t token.Token) string {
	if n, ok := operatorNames[t]; ok {
		return n
	}
	if t.IsKeyword() {
		return strings.ToUpper(t.String())
	}
	return t.String()
}

// tokenLit formats a scanner literal for a one-line dump. Keywords carry no literal;
// literals containing line breaks (raw strings, block comments, auto semicolons)
// are Go-quoted so each token stays on one line.
func tokenLit(t token.Token, lit string) string {
	if t.IsKeyword() {
		return ""
	}
	if strings.ContainsAny(lit, "\n\r") {
		return strconv.Quote(lit)
	}
	return lit
}

// dumpTokens scans src with comments enabled. It returns the dump and the number of
// scanner errors (reported as ERROR lines after EOF).
func dumpTokens(name string, src []byte) ([]byte, int) {
	fset := token.NewFileSet()
	file := fset.AddFile(name, -1, len(src))
	var errs []string
	var s scanner.Scanner
	s.Init(file, src, func(pos token.Position, msg string) {
		errs = append(errs, fmt.Sprintf("ERROR %d:%d %s", pos.Line, pos.Column, oneLine(msg)))
	}, scanner.ScanComments)
	var buf bytes.Buffer
	for {
		pos, tok, lit := s.Scan()
		if tok == token.EOF {
			fmt.Fprintf(&buf, "%d\tEOF\t\n", file.Offset(pos))
			break
		}
		fmt.Fprintf(&buf, "%d\t%s\t%s\n", file.Offset(pos), tokenName(tok), tokenLit(tok, lit))
	}
	for _, e := range errs {
		buf.WriteString(e + "\n")
	}
	return buf.Bytes(), len(errs)
}

func oneLine(s string) string {
	return strings.NewReplacer("\r", " ", "\n", " ").Replace(s)
}
