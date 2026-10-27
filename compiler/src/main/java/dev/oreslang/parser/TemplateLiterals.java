package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtick strings are scanned as one token so braces, quotation marks and
 * comments inside interpolation do not escape into the surrounding program.
 * Lowering uses ordinary checked string concatenation: embedded expressions
 * must have string type, and are evaluated once from left to right.
 */
final class TemplateLiterals {
    private static final int MAX_NESTING = 32;
    private static final int MAX_BRACE_NESTING = 256;
    private static final int MAX_INTERPOLATIONS = 1024;

    private TemplateLiterals() { }

    static int closingBacktick(String source, int from) {
        return closingBacktick(source, from, 0);
    }

    private static int closingBacktick(String source, int from, int nesting) {
        requireDepth(nesting);
        int at = from;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c == '\\') {
                if (at + 1 >= source.length()) throw syntax("unterminated template escape");
                at += 2;
            } else if (c == '$' && at + 1 < source.length() && source.charAt(at + 1) == '{') {
                at = interpolationEnd(source, at + 2, nesting + 1) + 1;
            } else if (c == 0x60) {
                return at;
            } else {
                at++;
            }
        }
        throw syntax("unterminated template literal");
    }

    private static int interpolationEnd(String source, int from, int nesting) {
        requireDepth(nesting);
        int braces = 1;
        int at = from;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c == '"' || c == '\'') {
                at = skipQuoted(source, at + 1, c);
            } else if (c == 0x60) {
                at = closingBacktick(source, at + 1, nesting + 1) + 1;
            } else if (c == '/' && at + 1 < source.length() && source.charAt(at + 1) == '/') {
                at += 2;
                while (at < source.length() && source.charAt(at) != '\n') at++;
            } else if (c == '/' && at + 1 < source.length() && source.charAt(at + 1) == '*') {
                at = skipBlockComment(source, at + 2);
            } else if (c == '{') {
                if (++braces > MAX_BRACE_NESTING) {
                    throw syntax("template interpolation brace nesting limit exceeded");
                }
                at++;
            } else if (c == '}') {
                if (--braces == 0) return at;
                at++;
            } else {
                at++;
            }
        }
        throw syntax("unterminated template interpolation");
    }

    private static int skipQuoted(String source, int from, char quote) {
        int at = from;
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == '\\') {
                if (at == source.length()) throw syntax("unterminated quoted interpolation escape");
                at++;
            } else if (c == quote) {
                return at;
            }
        }
        throw syntax("unterminated quoted string inside interpolation");
    }

    private static int skipBlockComment(String source, int from) {
        int nesting = 1;
        int at = from;
        while (at < source.length()) {
            if (source.startsWith("/*", at)) { nesting++; at += 2; }
            else if (source.startsWith("*/", at)) {
                at += 2;
                if (--nesting == 0) return at;
            } else at++;
        }
        throw syntax("unterminated comment inside interpolation");
    }

    static Ast.Expr lower(String raw) {
        List<Ast.Expr> pieces = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int interpolations = 0;
        int at = 0;
        while (at < raw.length()) {
            char c = raw.charAt(at++);
            if (c == '\\') {
                if (at == raw.length()) throw syntax("unterminated template escape");
                char escaped = raw.charAt(at++);
                switch (escaped) {
                    case 'n' -> literal.append('\n');
                    case 'r' -> literal.append('\r');
                    case 't' -> literal.append('\t');
                    case '\\' -> literal.append('\\');
                    case '"' -> literal.append('"');
                    case '\'' -> literal.append('\'');
                    case '$' -> literal.append('$');
                    case 0x60 -> literal.append((char) 0x60);
                    case 'u' -> {
                        if (at + 4 > raw.length()) throw syntax("incomplete template Unicode escape");
                        int value = 0;
                        for (int j = 0; j < 4; j++) {
                            char hex = raw.charAt(at++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw syntax("invalid template Unicode escape");
                            value = (value << 4) | digit;
                        }
                        literal.append((char) value);
                    }
                    default -> throw syntax("invalid template escape");
                }
                continue;
            }
            if (c == '$' && at < raw.length() && raw.charAt(at) == '{') {
                if (++interpolations > MAX_INTERPOLATIONS)
                    throw syntax("template interpolation count limit exceeded");
                validateDecodedUnicode(literal);
                pieces.add(new Ast.LiteralExpr(literal.toString()));
                literal.setLength(0);
                int close = interpolationEnd(raw, at + 1, 0);
                String source = raw.substring(at + 1, close);
                if (source.isBlank()) throw syntax("empty template interpolation");
                pieces.add(Parser.parseInterpolatedExpression(source));
                at = close + 1;
                continue;
            }
            literal.append(c);
        }
        validateDecodedUnicode(literal);
        pieces.add(new Ast.LiteralExpr(literal.toString()));
        Ast.Expr result = pieces.getFirst();
        for (int i = 1; i < pieces.size(); i++)
            result = new Ast.BinaryExpr("+", result, pieces.get(i));
        return result;
    }

    private static void validateDecodedUnicode(CharSequence value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw syntax("template contains unpaired Unicode surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw syntax("template contains unpaired Unicode surrogate");
            }
        }
    }

    private static void requireDepth(int nesting) {
        if (nesting > MAX_NESTING) throw syntax("template interpolation nesting limit exceeded");
    }

    private static IllegalArgumentException syntax(String detail) {
        return new IllegalArgumentException("Oreslang template error: " + detail);
    }
}
