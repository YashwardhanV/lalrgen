package lalrgen.regex;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the regex syntax in docs/SPEC_FORMAT.md.
 *
 * <pre>
 * union   := concat ('|' concat)*
 * concat  := postfix*                      (empty concat = epsilon)
 * postfix := atom ('*' | '+' | '?' | '{m}' | '{m,}' | '{m,n}')*
 * atom    := '(' union ')' | '[' class ']' | '.' | '\' escape | literal
 * </pre>
 *
 * Operator precedence, highest first: postfix operators, concatenation, union. Sugar is removed
 * while parsing (see {@link RegexNode}).
 */
public final class RegexParser {
    /** Upper bound for {m,n} so a typo like a{100000} cannot explode the NFA. */
    public static final int MAX_REPEAT = 1000;

    private static final IntervalSet DIGITS = IntervalSet.range('0', '9');
    private static final IntervalSet WORD = IntervalSet.fromPairs(List.of(
            new int[] {'a', 'z'}, new int[] {'A', 'Z'}, new int[] {'0', '9'}, new int[] {'_', '_'}));
    private static final IntervalSet SPACE = IntervalSet.fromPairs(List.of(
            new int[] {' ', ' '}, new int[] {'\t', '\r'})); // \t \n \u000B \f \r

    private final int[] cps;
    private int i = 0;

    private RegexParser(String text) {
        this.cps = text.codePoints().toArray();
    }

    /** Parses a regex. Throws {@link RegexSyntaxException} with the offset of the problem. */
    public static RegexNode parse(String text) {
        RegexParser p = new RegexParser(text);
        RegexNode n = p.parseUnion();
        if (!p.atEnd()) {
            if (p.peek() == ')') throw new RegexSyntaxException("unmatched ')'", p.i);
            throw new RegexSyntaxException("unexpected '" + p.peekStr() + "'", p.i);
        }
        return n;
    }

    private RegexNode parseUnion() {
        RegexNode left = parseConcat();
        while (peek() == '|') {
            i++;
            left = new RegexNode.Union(left, parseConcat());
        }
        return left;
    }

    private RegexNode parseConcat() {
        RegexNode node = null;
        while (!atEnd() && peek() != '|' && peek() != ')') {
            RegexNode p = parsePostfix();
            node = node == null ? p : new RegexNode.Concat(node, p);
        }
        return node == null ? RegexNode.EPSILON : node;
    }

    private RegexNode parsePostfix() {
        RegexNode atom = parseAtom();
        while (!atEnd()) {
            int c = peek();
            if (c == '*') {
                i++;
                atom = new RegexNode.Star(atom);
            } else if (c == '+') {
                i++;
                atom = new RegexNode.Concat(atom, new RegexNode.Star(atom));
            } else if (c == '?') {
                i++;
                atom = new RegexNode.Union(atom, RegexNode.EPSILON);
            } else if (c == '{') {
                atom = parseRepeat(atom);
            } else {
                break;
            }
        }
        return atom;
    }

    /** Parses {m}, {m,} or {m,n} after an atom and expands it. */
    private RegexNode parseRepeat(RegexNode atom) {
        int open = i;
        i++; // '{'
        int m = readInt(open);
        int n;
        if (peek() == '}') {
            n = m;
        } else if (peek() == ',') {
            i++;
            n = peek() == '}' ? -1 : readInt(open);
        } else {
            throw new RegexSyntaxException("malformed repetition, expected {m}, {m,} or {m,n}", open);
        }
        if (peek() != '}') throw new RegexSyntaxException("missing '}' in repetition", open);
        i++;
        if (n != -1 && n < m) throw new RegexSyntaxException("repetition {" + m + "," + n + "} has max < min", open);
        if (m > MAX_REPEAT || n > MAX_REPEAT) {
            throw new RegexSyntaxException("repetition count above " + MAX_REPEAT, open);
        }
        RegexNode result = RegexNode.EPSILON;
        for (int k = 0; k < m; k++) result = concat(result, atom);
        if (n == -1) {
            result = concat(result, new RegexNode.Star(atom));
        } else {
            for (int k = 0; k < n - m; k++) result = concat(result, new RegexNode.Union(atom, RegexNode.EPSILON));
        }
        return result;
    }

    private static RegexNode concat(RegexNode a, RegexNode b) {
        if (a instanceof RegexNode.Epsilon) return b;
        if (b instanceof RegexNode.Epsilon) return a;
        return new RegexNode.Concat(a, b);
    }

    private int readInt(int errAt) {
        if (!(peek() >= '0' && peek() <= '9')) throw new RegexSyntaxException("expected a number in repetition", errAt);
        long v = 0;
        while (peek() >= '0' && peek() <= '9') {
            v = v * 10 + (cps[i++] - '0');
            if (v > Integer.MAX_VALUE / 2) throw new RegexSyntaxException("repetition count too large", errAt);
        }
        return (int) v;
    }

    private RegexNode parseAtom() {
        int start = i;
        int c = peek();
        switch (c) {
            case '(' -> {
                i++;
                RegexNode inner = parseUnion();
                if (peek() != ')') throw new RegexSyntaxException("missing ')'", start);
                i++;
                return inner;
            }
            case '[' -> {
                return new RegexNode.CharSet(parseClass());
            }
            case '.' -> {
                i++;
                return new RegexNode.CharSet(IntervalSet.of('\n').complement());
            }
            case '\\' -> {
                return new RegexNode.CharSet(parseEscape());
            }
            case '*', '+', '?', '{' -> throw new RegexSyntaxException("nothing to repeat before '" + (char) c + "'", start);
            default -> {
                i++;
                return new RegexNode.CharSet(IntervalSet.of(c));
            }
        }
    }

    /** Parses [...] or [^...]. A ']' right after '[' or '[^' is a literal. */
    private IntervalSet parseClass() {
        int start = i;
        i++; // '['
        boolean negate = false;
        if (peek() == '^') {
            negate = true;
            i++;
        }
        List<int[]> pairs = new ArrayList<>();
        IntervalSet extra = IntervalSet.EMPTY;
        boolean first = true;
        while (true) {
            if (atEnd()) throw new RegexSyntaxException("unterminated character class", start);
            if (peek() == ']' && !first) {
                i++;
                break;
            }
            first = false;
            int lo;
            int atomStart = i;
            if (peek() == '\\') {
                IntervalSet s = parseEscape();
                if (!(s.rangeCount() == 1 && s.lo(0) == s.hi(0))) {
                    extra = extra.union(s); // \d, \w, \s inside a class
                    continue;
                }
                lo = s.lo(0);
            } else {
                lo = cps[i++];
            }
            if (peek() == '-' && peek(1) != ']' && peek(1) != -1) {
                i++; // '-'
                int hi;
                if (peek() == '\\') {
                    IntervalSet s = parseEscape();
                    if (!(s.rangeCount() == 1 && s.lo(0) == s.hi(0))) {
                        throw new RegexSyntaxException("a range cannot end with a class escape", atomStart);
                    }
                    hi = s.lo(0);
                } else {
                    hi = cps[i++];
                }
                if (hi < lo) {
                    throw new RegexSyntaxException("range out of order: " + IntervalSet.show(lo) + "-" + IntervalSet.show(hi), atomStart);
                }
                pairs.add(new int[] {lo, hi});
            } else {
                pairs.add(new int[] {lo, lo});
            }
        }
        IntervalSet set = IntervalSet.fromPairs(pairs).union(extra);
        if (negate) set = set.complement();
        if (set.isEmpty()) throw new RegexSyntaxException("character class matches nothing", start);
        return set;
    }

    /** Parses a backslash escape and returns the set of code points it denotes. */
    private IntervalSet parseEscape() {
        int start = i;
        i++; // backslash
        if (atEnd()) throw new RegexSyntaxException("trailing backslash", start);
        int c = cps[i++];
        return switch (c) {
            case 'n' -> IntervalSet.of('\n');
            case 't' -> IntervalSet.of('\t');
            case 'r' -> IntervalSet.of('\r');
            case 'f' -> IntervalSet.of('\f');
            case 'v' -> IntervalSet.of(0x0B);
            case '0' -> IntervalSet.of(0);
            case 'd' -> DIGITS;
            case 'D' -> DIGITS.complement();
            case 'w' -> WORD;
            case 'W' -> WORD.complement();
            case 's' -> SPACE;
            case 'S' -> SPACE.complement();
            case 'x' -> IntervalSet.of(readHex(2, start));
            case 'u' -> {
                if (peek() == '{') {
                    i++;
                    int v = 0;
                    int digits = 0;
                    while (peek() != '}') {
                        int d = Character.digit(peek(), 16);
                        if (d < 0 || digits > 6) throw new RegexSyntaxException("bad \\u{...} escape", start);
                        v = v * 16 + d;
                        digits++;
                        i++;
                    }
                    i++;
                    if (digits == 0 || v > IntervalSet.MAX_CODE_POINT) throw new RegexSyntaxException("bad \\u{...} escape", start);
                    yield IntervalSet.of(v);
                }
                yield IntervalSet.of(readHex(4, start));
            }
            default -> {
                if (Character.isLetterOrDigit(c)) {
                    throw new RegexSyntaxException("unknown escape '\\" + new String(Character.toChars(c)) + "'", start);
                }
                yield IntervalSet.of(c); // \. \* \/ \\ \- etc.
            }
        };
    }

    private int readHex(int n, int errAt) {
        int v = 0;
        for (int k = 0; k < n; k++) {
            int d = Character.digit(peek(), 16);
            if (d < 0) throw new RegexSyntaxException("expected " + n + " hex digits", errAt);
            v = v * 16 + d;
            i++;
        }
        return v;
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

    private String peekStr() {
        return new String(Character.toChars(peek()));
    }
}
