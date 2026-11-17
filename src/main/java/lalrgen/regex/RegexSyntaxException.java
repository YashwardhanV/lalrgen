package lalrgen.regex;

/** A regex syntax error. {@code offset} is the 0-based code point index into the regex text. */
public class RegexSyntaxException extends RuntimeException {
    private final int offset;

    public RegexSyntaxException(String message, int offset) {
        super(message);
        this.offset = offset;
    }

    public int offset() {
        return offset;
    }
}
