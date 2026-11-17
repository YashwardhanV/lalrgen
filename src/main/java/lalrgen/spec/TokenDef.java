package lalrgen.spec;

/**
 * One lexical rule from the declarations section.
 *
 * @param name  token name (for %skip rules a generated name like "%skip#1")
 * @param regex regex source text, without the surrounding slashes
 * @param kind  REGEX for %token, SKIP for %skip
 * @param pos   position of the directive
 * @param regexPos position of the first character of the regex text
 */
public record TokenDef(String name, String regex, Kind kind, Pos pos, Pos regexPos) {
    public enum Kind { REGEX, SKIP }
}
