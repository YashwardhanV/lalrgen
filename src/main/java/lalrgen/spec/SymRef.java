package lalrgen.spec;

/**
 * A reference to a grammar symbol inside a rule or directive.
 *
 * @param name    for a NAME: the name; for a quoted literal: the display form with quotes, e.g. "'+'"
 * @param literal true if this is a quoted literal
 * @param text    for literals, the unescaped literal text (e.g. "+"); for names equal to name
 */
public record SymRef(String name, boolean literal, String text, Pos pos) {
    public static SymRef name(String name, Pos pos) {
        return new SymRef(name, false, name, pos);
    }

    public static SymRef literal(String text, Pos pos) {
        return new SymRef(displayLiteral(text), true, text, pos);
    }

    /** Display form of a literal: wrapped in single quotes, with quotes, backslashes and controls escaped. */
    public static String displayLiteral(String text) {
        StringBuilder sb = new StringBuilder("'");
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '\'' -> sb.append("\\'");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.appendCodePoint(cp);
            }
        });
        return sb.append('\'').toString();
    }
}
