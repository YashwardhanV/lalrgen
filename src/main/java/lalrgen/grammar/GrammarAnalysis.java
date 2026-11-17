package lalrgen.grammar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * Nullable, FIRST and FOLLOW sets, plus reachability and shortest terminal derivations.
 * Everything is computed by fixed-point iteration: keep applying the rules until no set changes.
 *
 * <pre>
 * nullable(A)  if some A -> X1..Xn has every Xi nullable (n = 0 counts)
 * FIRST(t)   = {t} for a terminal t
 * FIRST(A)  += FIRST(Xi) for each A -> X1..Xn, while X1..X(i-1) are all nullable
 * FOLLOW(S') = {$}
 * for A -> alpha B beta:  FOLLOW(B) += FIRST(beta);  if beta is nullable: FOLLOW(B) += FOLLOW(A)
 * </pre>
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), section 4.4.2.
 */
public final class GrammarAnalysis {
    public static final int INF = Integer.MAX_VALUE / 4;

    private final Grammar g;
    private final boolean[] nullable;
    private final BitSet[] first;
    private final BitSet[] follow;
    private final int[] minLength;
    private final int[] minProduction;
    private final boolean[] reachable;

    public GrammarAnalysis(Grammar g) {
        this.g = g;
        int n = g.symbolCount();
        nullable = new boolean[n];
        first = new BitSet[n];
        follow = new BitSet[n];
        for (int s = 0; s < n; s++) {
            first[s] = new BitSet();
            follow[s] = new BitSet();
            if (g.isTerminal(s)) first[s].set(s);
        }
        computeNullable();
        computeFirst();
        computeFollow();
        minLength = new int[n];
        minProduction = new int[n];
        computeShortestDerivations();
        reachable = new boolean[n];
        computeReachable();
    }

    private void computeNullable() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                if (nullable[p.lhs()]) continue;
                boolean all = true;
                for (int s : p.rhs()) {
                    if (!nullable[s]) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    nullable[p.lhs()] = true;
                    changed = true;
                }
            }
        }
    }

    private void computeFirst() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                BitSet target = first[p.lhs()];
                int before = target.cardinality();
                for (int s : p.rhs()) {
                    target.or(first[s]);
                    if (!nullable[s]) break;
                }
                if (target.cardinality() != before) changed = true;
            }
        }
    }

    private void computeFollow() {
        follow[g.augmentedStart()].set(Grammar.EOF);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                int[] rhs = p.rhs();
                for (int i = 0; i < rhs.length; i++) {
                    int b = rhs[i];
                    if (g.isTerminal(b)) continue;
                    BitSet target = follow[b];
                    int before = target.cardinality();
                    target.or(firstOf(rhs, i + 1));
                    if (isNullable(rhs, i + 1)) target.or(follow[p.lhs()]);
                    if (target.cardinality() != before) changed = true;
                }
            }
        }
    }

    /**
     * Shortest terminal string derivable from each symbol (Bellman-Ford style relaxation).
     * minLength is INF for a non-terminal that derives no terminal string at all (non-productive).
     */
    private void computeShortestDerivations() {
        Arrays.fill(minLength, INF);
        Arrays.fill(minProduction, -1);
        for (int t = 0; t < g.terminalCount(); t++) minLength[t] = 1;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Production p : g.productions()) {
                long sum = 0;
                for (int s : p.rhs()) sum += minLength[s];
                if (sum < minLength[p.lhs()]) {
                    minLength[p.lhs()] = (int) sum;
                    minProduction[p.lhs()] = p.id();
                    changed = true;
                }
            }
        }
    }

    private void computeReachable() {
        Deque<Integer> work = new ArrayDeque<>();
        reachable[g.augmentedStart()] = true;
        work.add(g.augmentedStart());
        while (!work.isEmpty()) {
            int a = work.poll();
            for (Production p : g.productionsOf(a)) {
                for (int s : p.rhs()) {
                    if (!reachable[s]) {
                        reachable[s] = true;
                        if (!g.isTerminal(s)) work.add(s);
                    }
                }
            }
        }
    }

    public boolean nullable(int sym) {
        return nullable[sym];
    }

    /** FIRST(sym). Do not modify the returned set. */
    public BitSet first(int sym) {
        return first[sym];
    }

    /** FOLLOW(nonTerminal). Do not modify the returned set. */
    public BitSet follow(int sym) {
        return follow[sym];
    }

    /** FIRST of the suffix seq[from..]. */
    public BitSet firstOf(int[] seq, int from) {
        BitSet out = new BitSet();
        for (int i = from; i < seq.length; i++) {
            out.or(first[seq[i]]);
            if (!nullable[seq[i]]) break;
        }
        return out;
    }

    /** True if the suffix seq[from..] can derive the empty string. */
    public boolean isNullable(int[] seq, int from) {
        for (int i = from; i < seq.length; i++) if (!nullable[seq[i]]) return false;
        return true;
    }

    public boolean productive(int sym) {
        return minLength[sym] < INF;
    }

    public boolean reachable(int sym) {
        return reachable[sym];
    }

    /** A shortest terminal string derivable from sym, as terminal ids. Requires productive(sym). */
    public List<Integer> shortestExpansion(int sym) {
        List<Integer> out = new ArrayList<>();
        expand(sym, out);
        return out;
    }

    private void expand(int sym, List<Integer> out) {
        if (g.isTerminal(sym)) {
            out.add(sym);
            return;
        }
        int p = minProduction[sym];
        if (p < 0) throw new IllegalStateException(g.name(sym) + " is not productive");
        for (int s : g.production(p).rhs()) expand(s, out);
    }

    /** Formats a set of terminal ids as "{ a, b, c }". */
    public String format(BitSet set) {
        StringBuilder sb = new StringBuilder("{ ");
        boolean firstItem = true;
        for (int t = set.nextSetBit(0); t >= 0; t = set.nextSetBit(t + 1)) {
            if (!firstItem) sb.append(", ");
            sb.append(g.name(t));
            firstItem = false;
        }
        return sb.append(firstItem ? "}" : " }").toString();
    }

    /** Table of nullable / FIRST / FOLLOW for every non-terminal (the CLI "sets" command). */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        int w = 6;
        for (int a = g.terminalCount(); a < g.symbolCount(); a++) w = Math.max(w, g.name(a).length());
        sb.append(String.format("%-" + w + "s  %-8s  %s%n", "symbol", "nullable", "FIRST / FOLLOW"));
        for (int a = g.terminalCount(); a < g.symbolCount(); a++) {
            sb.append(String.format("%-" + w + "s  %-8s  FIRST  = %s%n", g.name(a), nullable[a] ? "yes" : "no", format(first[a])));
            sb.append(String.format("%-" + w + "s  %-8s  FOLLOW = %s%n", "", "", format(follow[a])));
        }
        return sb.toString();
    }
}
