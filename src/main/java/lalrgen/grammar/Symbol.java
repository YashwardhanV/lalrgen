package lalrgen.grammar;

/** A grammar symbol. Terminals have ids 0..T-1 ($ is 0); non-terminals have ids T..N-1. */
public record Symbol(int id, String name, boolean terminal) {
    @Override
    public String toString() {
        return name;
    }
}
