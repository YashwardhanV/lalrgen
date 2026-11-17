package lalrgen.spec;

/** An error or warning with a source position. */
public record Diagnostic(Severity severity, String file, Pos pos, String message) {

    public enum Severity { ERROR, WARNING }

    public static Diagnostic error(String file, Pos pos, String message) {
        return new Diagnostic(Severity.ERROR, file, pos, message);
    }

    public static Diagnostic warning(String file, Pos pos, String message) {
        return new Diagnostic(Severity.WARNING, file, pos, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        String where = (file == null ? "" : file + ":") + pos;
        return where + ": " + (isError() ? "error" : "warning") + ": " + message;
    }
}
