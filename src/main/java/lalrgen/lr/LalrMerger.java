package lalrgen.lr;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LALR(1) by merging: take the canonical LR(1) collection and merge every group of states that
 * have the same core (the same LR(0) items, ignoring lookaheads), taking the union of the
 * lookaheads. Transitions stay consistent because goto depends only on the core.
 *
 * <p>Why merging can create reduce/reduce conflicts but never shift/reduce ones: shift actions
 * depend only on the core, which is identical in all merged states. A new shift/reduce conflict
 * would need a reduce item on lookahead t together with a shift on t; the shift exists in every
 * merged state, so one of the original LR(1) states already had that conflict. Two reduce items
 * from different states, however, can end up sharing a lookahead after the union.
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), section 4.7.4.
 */
public final class LalrMerger {
    private LalrMerger() {}

    public static LRAutomaton merge(LRAutomaton lr1) {
        if (lr1.kind() != LRAutomaton.Kind.LR1) throw new IllegalArgumentException("need an LR(1) automaton");
        int n = lr1.stateCount();
        int symbols = lr1.grammar().symbolCount();

        // group LR(1) states by core, in order of first appearance (keeps numbering deterministic)
        Map<List<LR0Item>, Integer> groupOfCore = new LinkedHashMap<>();
        int[] group = new int[n];
        List<List<Integer>> members = new ArrayList<>();
        for (LRState st : lr1.states()) {
            List<LR0Item> core = List.copyOf(st.items().keySet());
            Integer gid = groupOfCore.get(core);
            if (gid == null) {
                gid = members.size();
                groupOfCore.put(core, gid);
                members.add(new ArrayList<>());
            }
            group[st.id()] = gid;
            members.get(gid).add(st.id());
        }

        List<LRState> merged = new ArrayList<>();
        for (int gid = 0; gid < members.size(); gid++) {
            TreeMap<LR0Item, BitSet> items = new TreeMap<>();
            for (int s : members.get(gid)) {
                for (Map.Entry<LR0Item, BitSet> e : lr1.state(s).items().entrySet()) {
                    items.computeIfAbsent(e.getKey(), k -> new BitSet()).or(e.getValue());
                }
            }
            LRState m = new LRState(gid, items, symbols);
            LRState first = lr1.state(members.get(gid).get(0));
            for (int sym = 0; sym < symbols; sym++) {
                int t = first.transition(sym);
                if (t < 0) continue;
                m.setTransition(sym, group[t]);
                for (int s : members.get(gid)) { // sanity check: all members agree
                    if (group[lr1.state(s).transition(sym)] != group[t]) {
                        throw new IllegalStateException("inconsistent transitions while merging LR(1) states");
                    }
                }
            }
            merged.add(m);
        }
        return new LRAutomaton(LRAutomaton.Kind.LALR, lr1.grammar(), merged);
    }

    /** For each LALR state, which LR(1) states were merged into it (for explanations). */
    public static List<List<Integer>> mergedFrom(LRAutomaton lr1) {
        Map<List<LR0Item>, List<Integer>> byCore = new LinkedHashMap<>();
        for (LRState st : lr1.states()) {
            byCore.computeIfAbsent(List.copyOf(st.items().keySet()), k -> new ArrayList<>()).add(st.id());
        }
        return new ArrayList<>(byCore.values());
    }
}
