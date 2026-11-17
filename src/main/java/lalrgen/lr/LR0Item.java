package lalrgen.lr;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.Production;

/**
 * An LR(0) item: a production with a dot showing how much of it has been seen.
 * {@code E -> E . '+' T} means "an E has been seen; '+' T is expected next".
 * An LR(1) item is an LR(0) item (its "core") plus one lookahead terminal; this project stores
 * LR(1) states as a map from core item to the set of its lookaheads.
 */
public record LR0Item(int production, int dot) implements Comparable<LR0Item> {

    @Override
    public int compareTo(LR0Item o) {
        if (production != o.production) return Integer.compare(production, o.production);
        return Integer.compare(dot, o.dot);
    }

    /** True if the dot is at the end (a "reduce item"). */
    public boolean isComplete(Grammar g) {
        return dot == g.production(production).length();
    }

    /** The symbol right after the dot, or -1 if the item is complete. */
    public int next(Grammar g) {
        Production p = g.production(production);
        return dot < p.length() ? p.rhs()[dot] : -1;
    }

    /** Kernel items: the start item, or any item whose dot is not at the far left. */
    public boolean isKernel() {
        return dot > 0 || production == 0;
    }

    public String show(Grammar g) {
        Production p = g.production(production);
        StringBuilder sb = new StringBuilder(g.name(p.lhs())).append(" ->");
        for (int i = 0; i < p.length(); i++) {
            if (i == dot) sb.append(" .");
            sb.append(' ').append(g.name(p.rhs()[i]));
        }
        if (dot == p.length()) sb.append(" .");
        return sb.toString();
    }
}
