package lalrgen.lexer;

/**
 * A token produced by the lexer.
 *
 * @param rule  index of the {@link TokenRule} that matched, or -1 for end of input
 * @param kind  token name (e.g. NUM or '+'), "$" for end of input
 * @param text  the matched source text
 */
public record Token(int rule, String kind, String text, int line, int col) {
    public static final String EOF_NAME = "$";

    public static Token eof(int line, int col) {
        return new Token(-1, EOF_NAME, "", line, col);
    }

    public boolean isEof() {
        return rule < 0;
    }

    @Override
    public String toString() {
        return line + ":" + col + "\t" + kind + (isEof() ? "" : "\t" + quote(text));
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.appendCodePoint(cp);
            }
        });
        return sb.append('"').toString();
    }
}
