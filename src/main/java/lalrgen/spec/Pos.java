package lalrgen.spec;

/** A 1-based line and column position in a source file (spec file or input file). */
public record Pos(int line, int col) {
    public static final Pos NONE = new Pos(0, 0);

    @Override
    public String toString() {
        return line + ":" + col;
    }
}
