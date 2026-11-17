package lalrgen.lexer;

import lalrgen.regex.RegexNode;
import lalrgen.spec.Pos;

/**
 * One lexical rule. {@code index} is both its position in the lexer and its priority:
 * a lower index wins when two rules match the same longest string.
 *
 * @param name    display name: a %token name, a quoted literal like "'+'", or "%skip#n"
 * @param source  the regex text or literal text, for messages
 */
public record TokenRule(int index, String name, Kind kind, RegexNode regex, String source, Pos pos) {
    public enum Kind { LITERAL, REGEX, SKIP }

    public boolean isSkip() {
        return kind == Kind.SKIP;
    }
}
