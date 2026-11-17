package lalrgen.automata;

import lalrgen.regex.IntervalSet;
import lalrgen.regex.RegexNode;
import lalrgen.regex.RegexParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2/M3 property test: for many regexes and random strings, java.util.regex (the oracle), the
 * Thompson NFA, the subset-construction DFA and both minimized DFAs must all agree.
 * Half the strings are sampled from the regex's own language so accepting paths get tested too.
 */
class RegexOracleTest {
    private static final String ALPHABET = "abcdwxyz019_.-+*/\\\" \n\té";
    private static final int STRINGS_PER_REGEX = 600;

    @ParameterizedTest
    @ValueSource(strings = {
        "a", "abc", "a|b", "a*", "a+", "a?", "(ab)*", "(a|b)*abb", "[a-c]+", "[^a]", "[^\\n]*", ".", ".*x",
        "a{3}", "a{2,}", "a{1,3}", "(a|bc){2}", "[0-9]+(\\.[0-9]+)?", "[a-zA-Z_][a-zA-Z0-9_]*", "\\d+", "\\w+",
        "\\s", "x(y|z)*w", "(a*b*)*", "((a|b)(c|d))+", "[-a]", "[a-]", "\\.\\*", "a|", "(|a)b", "[ab]{0,2}c",
        "(ab|a)(bc|c)", "\"([^\"\\\\]|\\\\.)*\"", "//[^\\n]*", "/\\*([^*]|\\*+[^*/])*\\*+/", "0x[0-9a-fA-F]+",
        "[\\-+]?\\d", "(a|b|c|d)*d(a|b|c|d){2}", "é+", "[^abc]+x", "(x|y)(x|y)?(x|y)?z"
    })
    void allStagesAgreeWithJavaRegex(String re) {
        Pattern oracle = Pattern.compile(re, Pattern.UNIX_LINES | Pattern.DOTALL * 0);
        RegexNode ast = RegexParser.parse(re);
        NFA nfa = Thompson.build(ast);
        DFA dfa = SubsetConstruction.build(nfa);
        DFA hop = Minimizer.hopcroft(dfa);
        DFA moore = Minimizer.moore(dfa);
        assertEquals(moore.stateCount(), hop.stateCount(), "Hopcroft and Moore disagree on the state count");
        Random rnd = new Random(re.hashCode());
        for (int k = 0; k < STRINGS_PER_REGEX; k++) {
            String s = k % 2 == 0 ? sample(ast, rnd, 0) : randomString(rnd);
            boolean expected = oracle.matcher(s).matches();
            String msg = "regex /" + re + "/ on " + show(s);
            assertEquals(expected, nfa.simulate(s) >= 0, msg + " (NFA)");
            assertEquals(expected, dfa.run(s) >= 0, msg + " (DFA)");
            assertEquals(expected, hop.run(s) >= 0, msg + " (Hopcroft)");
            assertEquals(expected, moore.run(s) >= 0, msg + " (Moore)");
        }
    }

    private static String randomString(Random rnd) {
        int len = rnd.nextInt(9);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** A random member of the regex's language. */
    static String sample(RegexNode n, Random rnd, int depth) {
        return switch (n) {
            case RegexNode.CharSet c -> new String(Character.toChars(pick(c.set(), rnd)));
            case RegexNode.Concat c -> sample(c.left(), rnd, depth + 1) + sample(c.right(), rnd, depth + 1);
            case RegexNode.Union u -> sample(rnd.nextBoolean() ? u.left() : u.right(), rnd, depth + 1);
            case RegexNode.Star s -> {
                StringBuilder sb = new StringBuilder();
                int reps = depth > 12 ? 0 : rnd.nextInt(4);
                for (int i = 0; i < reps; i++) sb.append(sample(s.inner(), rnd, depth + 1));
                yield sb.toString();
            }
            case RegexNode.Epsilon e -> "";
        };
    }

    /** Prefers characters from the test alphabet, otherwise any non-surrogate member. */
    private static int pick(IntervalSet set, Random rnd) {
        if (rnd.nextInt(5) > 0) {
            int[] candidates = ALPHABET.codePoints().filter(set::contains).toArray();
            if (candidates.length > 0) return candidates[rnd.nextInt(candidates.length)];
        }
        for (int tries = 0; tries < 100; tries++) {
            int k = rnd.nextInt(set.rangeCount());
            long span = (long) set.hi(k) - set.lo(k) + 1;
            int cp = (int) (set.lo(k) + (long) (rnd.nextDouble() * span));
            if (cp < 0xD800 || cp > 0xDFFF) return cp;
        }
        return set.lo(0);
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
