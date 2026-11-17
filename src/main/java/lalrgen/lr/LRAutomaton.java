package lalrgen.lr;

import lalrgen.grammar.Grammar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The characteristic automaton of a grammar: states are item sets, edges are labelled with grammar
 * symbols. It is itself a DFA, recognising the "viable prefixes" (stack contents that can still
 * lead to a successful parse). This is the same subset-style construction as NFA to DFA, applied
 * to items instead of NFA states.
 */
public final class LRAutomaton {
    public enum Kind { LR0, LR1, LALR }

    private final Kind kind;
    private final Grammar grammar;
    private final List<LRState> states;
    private int[] parent;
    private int[] parentSymbol;

    LRAutomaton(Kind kind, Grammar grammar, List<LRState> states) {
        this.kind = kind;
        this.grammar = grammar;
        this.states = Collections.unmodifiableList(new ArrayList<>(states));
    }

    public Kind kind() {
        return kind;
    }

    public Grammar grammar() {
        return grammar;
    }

    public List<LRState> states() {
        return states;
    }

    public LRState state(int id) {
        return states.get(id);
    }

    public int stateCount() {
        return states.size();
    }

    public boolean hasLookaheads() {
        return kind != Kind.LR0;
    }

    /**
     * Shortest sequence of grammar symbols that leads from state 0 to the given state (a shortest
     * viable prefix). Found by breadth-first search; ties broken by symbol id, so it is
     * deterministic. Used to explain conflicts.
     */
    public synchronized List<Integer> shortestPrefix(int target) {
        if (parent == null) {
            parent = new int[states.size()];
            parentSymbol = new int[states.size()];
            Arrays.fill(parent, -2);
            parent[0] = -1;
            Deque<Integer> q = new ArrayDeque<>();
            q.add(0);
            while (!q.isEmpty()) {
                int s = q.poll();
                for (int sym = 0; sym < grammar.symbolCount(); sym++) {
                    int t = states.get(s).transition(sym);
                    if (t >= 0 && parent[t] == -2) {
                        parent[t] = s;
                        parentSymbol[t] = sym;
                        q.add(t);
                    }
                }
            }
        }
        List<Integer> path = new ArrayList<>();
        for (int s = target; s > 0; s = parent[s]) path.add(parentSymbol[s]);
        Collections.reverse(path);
        return path;
    }

    /** Text listing of every state with its items, lookaheads and transitions. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (LRState st : states) {
            sb.append(describeState(st.id())).append('\n');
        }
        return sb.toString();
    }

    public String describeState(int id) {
        LRState st = states.get(id);
        StringBuilder sb = new StringBuilder("State ").append(id).append('\n');
        for (Map.Entry<LR0Item, BitSet> e : kernelFirst(st)) {
            sb.append(e.getKey().isKernel() ? "    " : "  + ").append(e.getKey().show(grammar));
            if (hasLookaheads()) sb.append("    [").append(names(e.getValue())).append(']');
            sb.append('\n');
        }
        for (int sym = 0; sym < grammar.symbolCount(); sym++) {
            int t = st.transition(sym);
            if (t >= 0) sb.append("    on ").append(grammar.name(sym)).append(" -> ").append(t).append('\n');
        }
        return sb.toString();
    }

    /** A state's items with the kernel items first, then the closure items ("+" in listings). */
    public static List<Map.Entry<LR0Item, BitSet>> kernelFirst(LRState st) {
        List<Map.Entry<LR0Item, BitSet>> out = new ArrayList<>();
        for (Map.Entry<LR0Item, BitSet> e : st.items().entrySet()) if (e.getKey().isKernel()) out.add(e);
        for (Map.Entry<LR0Item, BitSet> e : st.items().entrySet()) if (!e.getKey().isKernel()) out.add(e);
        return out;
    }

    public String names(BitSet set) {
        StringBuilder sb = new StringBuilder();
        for (int t = set.nextSetBit(0); t >= 0; t = set.nextSetBit(t + 1)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(grammar.name(t));
        }
        return sb.toString();
    }
}
