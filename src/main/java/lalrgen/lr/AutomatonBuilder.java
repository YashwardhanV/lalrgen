package lalrgen.lr;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.grammar.Production;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Builds the canonical collection of LR(0) or LR(1) item sets.
 *
 * <pre>
 * closure(I):  for each item [A -> alpha . B beta, a] in I and each production B -> gamma,
 *              add [B -> . gamma, b] for every b in FIRST(beta a).        (LR(0): ignore lookaheads)
 * goto(I, X):  closure of { [A -> alpha X . beta, a] : [A -> alpha . X beta, a] in I }
 * collection:  start from closure({[S' -> . S, $]}); breadth-first, for each state and each
 *              symbol X in id order, add goto(I, X) if it is new.
 * </pre>
 *
 * States are identified by their kernel (plus lookaheads for LR(1)), since closure is a function
 * of the kernel. The breadth-first order makes state numbers deterministic.
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), sections 4.6.2 and 4.7.2.
 */
public final class AutomatonBuilder {
    private final Grammar g;
    private final boolean lr1;
    /** firstAfter[p][d] = FIRST(rhs[d+1..]) of production p; nullableAfter likewise. */
    private final BitSet[][] firstAfter;
    private final boolean[][] nullableAfter;

    private AutomatonBuilder(Grammar g, GrammarAnalysis a, boolean lr1) {
        this.g = g;
        this.lr1 = lr1;
        int n = g.productions().size();
        firstAfter = new BitSet[n][];
        nullableAfter = new boolean[n][];
        for (Production p : g.productions()) {
            firstAfter[p.id()] = new BitSet[p.length() + 1];
            nullableAfter[p.id()] = new boolean[p.length() + 1];
            for (int d = 0; d <= p.length(); d++) {
                firstAfter[p.id()][d] = a.firstOf(p.rhs(), Math.min(d + 1, p.length()));
                nullableAfter[p.id()][d] = a.isNullable(p.rhs(), Math.min(d + 1, p.length()));
            }
        }
    }

    public static LRAutomaton buildLR0(Grammar g, GrammarAnalysis a) {
        return new AutomatonBuilder(g, a, false).build(LRAutomaton.Kind.LR0);
    }

    public static LRAutomaton buildLR1(Grammar g, GrammarAnalysis a) {
        return new AutomatonBuilder(g, a, true).build(LRAutomaton.Kind.LR1);
    }

    private record Key(List<LR0Item> items, List<BitSet> lookaheads) {}

    private LRAutomaton build(LRAutomaton.Kind kind) {
        List<LRState> states = new ArrayList<>();
        Map<Key, Integer> index = new HashMap<>();

        TreeMap<LR0Item, BitSet> startKernel = new TreeMap<>();
        BitSet startLa = new BitSet();
        if (lr1) startLa.set(Grammar.EOF);
        startKernel.put(new LR0Item(0, 0), startLa);
        index.put(keyOf(startKernel), 0);
        states.add(new LRState(0, closure(startKernel), g.symbolCount()));

        for (int i = 0; i < states.size(); i++) { // states grows while we iterate: breadth-first
            LRState st = states.get(i);
            // group the items that can advance, by the symbol after the dot
            TreeMap<Integer, TreeMap<LR0Item, BitSet>> kernels = new TreeMap<>();
            for (Map.Entry<LR0Item, BitSet> e : st.items().entrySet()) {
                int x = e.getKey().next(g);
                if (x < 0) continue;
                LR0Item advanced = new LR0Item(e.getKey().production(), e.getKey().dot() + 1);
                kernels.computeIfAbsent(x, k -> new TreeMap<>()).put(advanced, (BitSet) e.getValue().clone());
            }
            for (Map.Entry<Integer, TreeMap<LR0Item, BitSet>> e : kernels.entrySet()) {
                Key key = keyOf(e.getValue());
                Integer target = index.get(key);
                if (target == null) {
                    target = states.size();
                    index.put(key, target);
                    states.add(new LRState(target, closure(e.getValue()), g.symbolCount()));
                }
                st.setTransition(e.getKey(), target);
            }
        }
        return new LRAutomaton(kind, g, states);
    }

    /**
     * Closure of a kernel. For LR(1), an item's lookaheads can grow after it was first added (a
     * second item may contribute more), so an item is re-processed whenever its set grows.
     */
    SortedMap<LR0Item, BitSet> closure(SortedMap<LR0Item, BitSet> kernel) {
        TreeMap<LR0Item, BitSet> result = new TreeMap<>();
        Deque<LR0Item> work = new ArrayDeque<>();
        for (Map.Entry<LR0Item, BitSet> e : kernel.entrySet()) {
            result.put(e.getKey(), (BitSet) e.getValue().clone());
            work.add(e.getKey());
        }
        while (!work.isEmpty()) {
            LR0Item item = work.poll();
            int b = item.next(g);
            if (b < 0 || g.isTerminal(b)) continue;
            BitSet la = new BitSet();
            if (lr1) {
                la.or(firstAfter[item.production()][item.dot()]);
                if (nullableAfter[item.production()][item.dot()]) la.or(result.get(item));
            }
            for (Production p : g.productionsOf(b)) {
                LR0Item fresh = new LR0Item(p.id(), 0);
                BitSet existing = result.get(fresh);
                if (existing == null) {
                    result.put(fresh, (BitSet) la.clone());
                    work.add(fresh);
                } else {
                    BitSet missing = (BitSet) la.clone();
                    missing.andNot(existing);
                    if (!missing.isEmpty()) {
                        existing.or(missing);
                        work.add(fresh);
                    }
                }
            }
        }
        return result;
    }

    private static Key keyOf(SortedMap<LR0Item, BitSet> kernel) {
        List<LR0Item> items = new ArrayList<>(kernel.keySet());
        List<BitSet> las = new ArrayList<>();
        for (BitSet b : kernel.values()) las.add((BitSet) b.clone());
        return new Key(List.copyOf(items), List.copyOf(las));
    }
}
