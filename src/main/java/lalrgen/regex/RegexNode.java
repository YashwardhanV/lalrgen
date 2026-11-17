package lalrgen.regex;

/**
 * Regex abstract syntax tree. The parser desugars everything into these five forms:
 * <ul>
 *   <li>a+ becomes Concat(a, Star(a))</li>
 *   <li>a? becomes Union(a, Epsilon)</li>
 *   <li>a{2,4} becomes a a (a|eps) (a|eps)</li>
 * </ul>
 * so Thompson's construction only needs one rule per form.
 */
public sealed interface RegexNode
        permits RegexNode.CharSet, RegexNode.Concat, RegexNode.Union, RegexNode.Star, RegexNode.Epsilon {

    /** Matches exactly one code point from the set. */
    record CharSet(IntervalSet set) implements RegexNode {}

    record Concat(RegexNode left, RegexNode right) implements RegexNode {}

    record Union(RegexNode left, RegexNode right) implements RegexNode {}

    record Star(RegexNode inner) implements RegexNode {}

    /** Matches the empty string. */
    record Epsilon() implements RegexNode {}

    Epsilon EPSILON = new Epsilon();

    /** True if the regex can match the empty string (used to reject a %token whose regex is a*). */
    static boolean nullable(RegexNode n) {
        return switch (n) {
            case CharSet c -> false;
            case Concat c -> nullable(c.left()) && nullable(c.right());
            case Union u -> nullable(u.left()) || nullable(u.right());
            case Star s -> true;
            case Epsilon e -> true;
        };
    }

    /** Builds a regex matching exactly the given string (used for quoted literal tokens). */
    static RegexNode literal(String text) {
        RegexNode result = null;
        for (int cp : text.codePoints().toArray()) {
            RegexNode c = new CharSet(IntervalSet.of(cp));
            result = result == null ? c : new Concat(result, c);
        }
        return result == null ? EPSILON : result;
    }

    /** Pretty-prints the AST back into regex syntax (for debugging and docs). */
    static String show(RegexNode n) {
        return switch (n) {
            case CharSet c -> c.set().toString();
            case Concat c -> showAtomic(c.left(), false) + showAtomic(c.right(), false);
            case Union u -> show(u.left()) + "|" + show(u.right());
            case Star s -> showAtomic(s.inner(), true) + "*";
            case Epsilon e -> "()";
        };
    }

    private static String showAtomic(RegexNode n, boolean forStar) {
        boolean needParens = n instanceof Union || (forStar && n instanceof Concat);
        return needParens ? "(" + show(n) + ")" : show(n);
    }
}
