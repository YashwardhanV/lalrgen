package lalrgen.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written recursive-descent parser for .lg spec files (format: docs/SPEC_FORMAT.md).
 *
 * <p>The parser works directly on characters instead of using a separate lexer, because the
 * spec language is context dependent: after {@code %token NAME} a regex in slashes follows,
 * and inside rules a quoted literal follows different escape rules.
 *
 * <p>It recovers from errors so several can be reported at once: a bad directive skips to the
 * end of its line, a bad rule skips to the next {@code ;}.
 */
public final class SpecParser {

    /** Internal signal used to unwind to the nearest recovery point. */
    private static final class Fail extends RuntimeException {
        Fail() {
            super(null, null, false, false);
        }
    }

    private final String file;
    private final int[] cps;
    private int i = 0;
    private int line = 1;
    private int col = 1;
    private final List<Diagnostic> errors = new ArrayList<>();
    private final List<Diagnostic> warnings = new ArrayList<>();

    private final List<TokenDef> tokens = new ArrayList<>();
    private final List<PrecDecl> precs = new ArrayList<>();
    private final List<SymRef> sync = new ArrayList<>();
    private final List<RuleDef> rules = new ArrayList<>();
    private SymRef start = null;
    private int skipCount = 0;

    private SpecParser(String file, String text) {
        this.file = file;
        this.cps = text.codePoints().toArray();
    }

    /** Parses a spec file. Throws {@link SpecException} listing every syntax error found. */
    public static SpecFile parse(String fileName, String text) {
        SpecParser p = new SpecParser(fileName, text);
        p.parseFile();
        if (!p.errors.isEmpty()) {
            throw new SpecException(p.errors);
        }
        return new SpecFile(fileName, List.copyOf(p.tokens), List.copyOf(p.precs), p.start,
                List.copyOf(p.sync), List.copyOf(p.rules), List.copyOf(p.warnings));
    }

    // ------------------------------------------------------------------ top level

    private void parseFile() {
        boolean sawSeparator = parseDeclarations();
        if (!sawSeparator) {
            error(pos(), "missing '%%' separator between declarations and rules");
            return;
        }
        parseRules();
        if (rules.isEmpty() && errors.isEmpty()) {
            error(pos(), "the rules section is empty; at least one rule is required");
        }
    }

    /** Parses directives until "%%". Returns false if end of file was reached first. */
    private boolean parseDeclarations() {
        while (true) {
            skipTrivia();
            if (atEnd()) return false;
            Pos p = pos();
            try {
                if (peek() != '%') {
                    error(p, "expected a directive such as %token, %skip or %left, found '"
                            + new String(Character.toChars(peek())) + "'");
                    throw new Fail();
                }
                if (peek(1) == '%') {
                    advance();
                    advance();
                    return true;
                }
                String d = readDirective();
                switch (d) {
                    case "%token" -> parseTokenDirective(p);
                    case "%skip" -> parseSkipDirective(p);
                    case "%left" -> parsePrecDirective(p, Assoc.LEFT);
                    case "%right" -> parsePrecDirective(p, Assoc.RIGHT);
                    case "%nonassoc" -> parsePrecDirective(p, Assoc.NONASSOC);
                    case "%start" -> parseStartDirective(p);
                    case "%sync" -> sync.addAll(readSymbolList(p, "%sync"));
                    default -> {
                        error(p, "unknown directive '" + d + "'");
                        throw new Fail();
                    }
                }
            } catch (Fail f) {
                skipToEndOfLine();
            }
        }
    }

    private void parseTokenDirective(Pos p) {
        skipInlineSpace();
        if (!isIdentStart(peek())) {
            error(pos(), "expected a token name after %token");
            throw new Fail();
        }
        String name = readIdent();
        skipInlineSpace();
        Pos rp = pos();
        String regex = readRegex();
        tokens.add(new TokenDef(name, regex, TokenDef.Kind.REGEX, p, new Pos(rp.line(), rp.col() + 1)));
    }

    private void parseSkipDirective(Pos p) {
        skipInlineSpace();
        Pos rp = pos();
        String regex = readRegex();
        skipCount++;
        tokens.add(new TokenDef("%skip#" + skipCount, regex, TokenDef.Kind.SKIP, p,
                new Pos(rp.line(), rp.col() + 1)));
    }

    private void parsePrecDirective(Pos p, Assoc assoc) {
        List<SymRef> names = readSymbolList(p, assoc == Assoc.LEFT ? "%left"
                : assoc == Assoc.RIGHT ? "%right" : "%nonassoc");
        precs.add(new PrecDecl(assoc, names, precs.size() + 1, p));
    }

    private void parseStartDirective(Pos p) {
        skipInlineSpace();
        Pos np = pos();
        if (!isIdentStart(peek())) {
            error(np, "expected a non-terminal name after %start");
            throw new Fail();
        }
        String name = readIdent();
        if (start != null) {
            error(p, "duplicate %start directive (first one at " + start.pos() + ")");
            return;
        }
        start = SymRef.name(name, np);
    }

    /** Reads names and quoted literals up to the next directive or end of file. */
    private List<SymRef> readSymbolList(Pos p, String directive) {
        List<SymRef> out = new ArrayList<>();
        while (true) {
            skipTrivia();
            if (atEnd() || peek() == '%') break;
            Pos sp = pos();
            if (isIdentStart(peek())) {
                out.add(SymRef.name(readIdent(), sp));
            } else if (peek() == '\'') {
                out.add(SymRef.literal(readQuoted(), sp));
            } else {
                error(sp, "unexpected character '" + new String(Character.toChars(peek()))
                        + "' in " + directive + " list");
                throw new Fail();
            }
        }
        if (out.isEmpty()) {
            error(p, directive + " needs at least one symbol");
        }
        return out;
    }

    // ------------------------------------------------------------------ rules

    private void parseRules() {
        while (true) {
            skipTrivia();
            if (atEnd()) return;
            try {
                parseRule();
            } catch (Fail f) {
                skipPast(';');
            }
        }
    }

    private void parseRule() {
        Pos p = pos();
        if (!isIdentStart(peek())) {
            error(p, "expected a rule name, found '" + new String(Character.toChars(peek())) + "'");
            throw new Fail();
        }
        String lhs = readIdent();
        skipTrivia();
        expect(':', "after rule name '" + lhs + "'");
        List<Alt> alts = new ArrayList<>();
        alts.add(parseAlt());
        while (true) {
            skipTrivia();
            if (peek() == '|') {
                advance();
                alts.add(parseAlt());
            } else if (peek() == ';') {
                advance();
                break;
            } else if (atEnd()) {
                error(pos(), "unexpected end of file: rule '" + lhs + "' is missing its ';'");
                throw new Fail();
            } else {
                error(pos(), "expected '|' or ';'");
                throw new Fail();
            }
        }
        rules.add(new RuleDef(lhs, List.copyOf(alts), p));
    }

    private Alt parseAlt() {
        skipTrivia();
        Pos altPos = pos();
        List<SymRef> symbols = new ArrayList<>();
        SymRef prec = null;
        AstExpr ast = null;
        boolean sawEmpty = false;
        while (true) {
            skipTrivia();
            int c = peek();
            Pos sp = pos();
            if (atEnd() || c == '|' || c == ';') break;
            if (ast != null) {
                error(sp, "nothing may follow the AST annotation except '|' or ';'");
                throw new Fail();
            }
            if (isIdentStart(c)) {
                String name = readIdent();
                skipTrivia();
                if (peek() == ':') {
                    error(sp, "unexpected ':' after '" + name + "' (missing ';' at the end of the previous rule?)");
                    throw new Fail();
                }
                if (prec != null) {
                    error(sp, "symbols may not follow %prec");
                    throw new Fail();
                }
                symbols.add(SymRef.name(name, sp));
            } else if (c == '\'') {
                if (prec != null) {
                    error(sp, "symbols may not follow %prec");
                    throw new Fail();
                }
                symbols.add(SymRef.literal(readQuoted(), sp));
            } else if (c == '%') {
                String d = readDirective();
                if (d.equals("%prec")) {
                    if (prec != null) {
                        error(sp, "duplicate %prec in one alternative");
                        throw new Fail();
                    }
                    skipTrivia();
                    Pos np = pos();
                    if (isIdentStart(peek())) {
                        prec = SymRef.name(readIdent(), np);
                    } else if (peek() == '\'') {
                        prec = SymRef.literal(readQuoted(), np);
                    } else {
                        error(np, "expected a token name or literal after %prec");
                        throw new Fail();
                    }
                } else if (d.equals("%empty")) {
                    sawEmpty = true;
                } else {
                    error(sp, "unexpected directive '" + d + "' inside a rule (only %prec and %empty are allowed)");
                    throw new Fail();
                }
            } else if (c == '-' && peek(1) == '>') {
                advance();
                advance();
                skipTrivia();
                ast = parseAst();
            } else {
                error(sp, "unexpected character '" + new String(Character.toChars(c)) + "' in rule");
                throw new Fail();
            }
        }
        if (sawEmpty && !symbols.isEmpty()) {
            error(altPos, "%empty cannot be combined with other symbols");
            throw new Fail();
        }
        return new Alt(List.copyOf(symbols), prec, ast, altPos);
    }

    private AstExpr parseAst() {
        Pos p = pos();
        if (peek() == '$') {
            advance();
            if (!isDigit(peek())) {
                error(pos(), "expected a child number after '$'");
                throw new Fail();
            }
            int n = 0;
            while (isDigit(peek())) {
                n = n * 10 + (advance() - '0');
                if (n > 1_000_000) {
                    error(p, "child number too large");
                    throw new Fail();
                }
            }
            if (n == 0) {
                error(p, "child numbers start at $1");
                throw new Fail();
            }
            return new AstExpr.ChildRef(n, p);
        }
        if (!isIdentStart(peek())) {
            error(p, "expected '$n' or a node name in the AST annotation");
            throw new Fail();
        }
        String name = readIdent();
        List<AstExpr> args = new ArrayList<>();
        skipTrivia();
        if (peek() == '(') {
            advance();
            skipTrivia();
            if (peek() != ')') {
                args.add(parseAst());
                skipTrivia();
                while (peek() == ',') {
                    advance();
                    skipTrivia();
                    args.add(parseAst());
                    skipTrivia();
                }
            }
            expect(')', "to close the argument list of " + name);
        }
        return new AstExpr.Ctor(name, List.copyOf(args), p);
    }

    // ------------------------------------------------------------------ lexical helpers

    private String readDirective() {
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(advance()); // '%'
        while (isIdentPart(peek())) sb.appendCodePoint(advance());
        return sb.toString();
    }

    private String readIdent() {
        StringBuilder sb = new StringBuilder();
        while (isIdentPart(peek())) sb.appendCodePoint(advance());
        return sb.toString();
    }

    /** Reads "/.../" and returns the inner text with escapes left intact for the regex parser. */
    private String readRegex() {
        Pos p = pos();
        if (peek() != '/') {
            error(p, "expected a regex in slashes, like /[0-9]+/");
            throw new Fail();
        }
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd() || peek() == '\n') {
                error(p, "unterminated regex (missing closing '/')");
                throw new Fail();
            }
            int c = advance();
            if (c == '/') break;
            sb.appendCodePoint(c);
            if (c == '\\') {
                if (atEnd() || peek() == '\n') {
                    error(p, "regex ends with a lone backslash");
                    throw new Fail();
                }
                sb.appendCodePoint(advance());
            }
        }
        if (sb.isEmpty()) {
            error(p, "empty regex");
            throw new Fail();
        }
        return sb.toString();
    }

    /** Reads a quoted literal 'text' and returns the unescaped text. */
    private String readQuoted() {
        Pos p = pos();
        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (atEnd() || peek() == '\n') {
                error(p, "unterminated literal (missing closing quote)");
                throw new Fail();
            }
            int c = advance();
            if (c == '\'') break;
            if (c == '\\') {
                if (atEnd()) {
                    error(p, "unterminated literal");
                    throw new Fail();
                }
                int e = advance();
                switch (e) {
                    case '\'' -> sb.append('\'');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> {
                        error(p, "unknown escape '\\" + new String(Character.toChars(e)) + "' in literal");
                        throw new Fail();
                    }
                }
            } else {
                sb.appendCodePoint(c);
            }
        }
        if (sb.isEmpty()) {
            error(p, "empty literal ''");
            throw new Fail();
        }
        return sb.toString();
    }

    private void expect(int c, String context) {
        skipTrivia();
        if (peek() != c) {
            String found = atEnd() ? "end of file" : "'" + new String(Character.toChars(peek())) + "'";
            error(pos(), "expected '" + new String(Character.toChars(c)) + "' " + context + ", found " + found);
            throw new Fail();
        }
        advance();
    }

    /** Skips whitespace and # comments. */
    private void skipTrivia() {
        while (!atEnd()) {
            int c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
            } else if (c == '#') {
                while (!atEnd() && peek() != '\n') advance();
            } else {
                return;
            }
        }
    }

    private void skipInlineSpace() {
        while (!atEnd() && (peek() == ' ' || peek() == '\t')) advance();
    }

    private void skipToEndOfLine() {
        while (!atEnd() && peek() != '\n') advance();
    }

    private void skipPast(int c) {
        while (!atEnd()) {
            int x = advance();
            if (x == c) return;
            if (x == '\'') { // do not stop at a ';' inside a literal
                while (!atEnd() && peek() != '\'' && peek() != '\n') advance();
                if (!atEnd() && peek() == '\'') advance();
            }
        }
    }

    private boolean atEnd() {
        return i >= cps.length;
    }

    private int peek() {
        return i < cps.length ? cps[i] : -1;
    }

    private int peek(int k) {
        return i + k < cps.length ? cps[i + k] : -1;
    }

    private int advance() {
        int c = cps[i++];
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private Pos pos() {
        return new Pos(line, col);
    }

    private static boolean isIdentStart(int c) {
        return c >= 0 && (Character.isLetter(c) || c == '_');
    }

    private static boolean isIdentPart(int c) {
        return c >= 0 && (Character.isLetterOrDigit(c) || c == '_');
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private void error(Pos p, String msg) {
        errors.add(Diagnostic.error(file, p, msg));
    }
}
