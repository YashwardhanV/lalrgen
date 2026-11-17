package lalrgen.spec;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when a spec file has one or more errors. Carries every diagnostic found. */
public class SpecException extends RuntimeException {
    private final List<Diagnostic> diagnostics;

    public SpecException(List<Diagnostic> diagnostics) {
        super(diagnostics.stream().map(Diagnostic::toString).collect(Collectors.joining("\n")));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
