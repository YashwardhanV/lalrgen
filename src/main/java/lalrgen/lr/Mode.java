package lalrgen.lr;

/** Which LR table construction to use. */
public enum Mode {
    /** LR(0) automaton, reduce on FOLLOW(lhs). */
    SLR,
    /** Canonical LR(1) automaton, reduce on each item's own lookahead. */
    LR1,
    /** LR(1) automaton with same-core states merged (what Yacc and Bison build). */
    LALR;

    public static Mode parse(String s) {
        return switch (s.toLowerCase()) {
            case "slr", "slr1", "slr(1)" -> SLR;
            case "lr1", "lr(1)", "clr", "canonical" -> LR1;
            case "lalr", "lalr1", "lalr(1)" -> LALR;
            default -> throw new IllegalArgumentException("unknown mode '" + s + "' (use slr, lr1 or lalr)");
        };
    }

    public String label() {
        return switch (this) {
            case SLR -> "slr";
            case LR1 -> "lr1";
            case LALR -> "lalr";
        };
    }
}
