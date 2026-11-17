package lalrgen.automata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Subset construction (NFA to DFA). Each DFA state stands for the set of NFA states the NFA
 * could be in at the same time.
 *
 * <pre>
 * D0 = eps-closure({nfa.start})
 * for each unmarked DFA state S, for each character class c:
 *     T = eps-closure(move(S, c))
 *     if T is empty: no transition (dead)
 *     else: add T as a new DFA state if unseen; trans[S][c] = T
 * </pre>
 *
 * A DFA state is accepting if it contains an accepting NFA state; when several token rules
 * accept, the one with the lowest tag (highest priority) wins.
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), section 3.7.1.
 */
public final class SubsetConstruction {
    private SubsetConstruction() {}

    public static DFA build(NFA nfa) {
        return build(nfa, CharClasses.build(nfa.labels()));
    }

    public static DFA build(NFA nfa, CharClasses classes) {
        int k = classes.count();
        // Precompute, for each NFA edge, which character classes it covers.
        List<List<BitSet>> edgeClasses = new ArrayList<>();
        for (int s = 0; s < nfa.stateCount(); s++) {
            List<BitSet> perEdge = new ArrayList<>();
            for (NFA.Edge e : nfa.edges(s)) perEdge.add(classes.classesOf(e.label()));
            edgeClasses.add(perEdge);
        }

        List<BitSet> states = new ArrayList<>();
        Map<BitSet, Integer> index = new HashMap<>();
        List<int[]> trans = new ArrayList<>();
        Deque<Integer> work = new ArrayDeque<>();

        BitSet startSet = new BitSet();
        startSet.set(nfa.start());
        startSet = nfa.epsilonClosure(startSet);
        states.add(startSet);
        index.put(startSet, 0);
        work.add(0);

        while (!work.isEmpty()) {
            int d = work.poll();
            BitSet set = states.get(d);
            int[] row = new int[k];
            for (int c = 0; c < k; c++) {
                BitSet moved = new BitSet();
                for (int s = set.nextSetBit(0); s >= 0; s = set.nextSetBit(s + 1)) {
                    List<NFA.Edge> es = nfa.edges(s);
                    for (int j = 0; j < es.size(); j++) {
                        if (edgeClasses.get(s).get(j).get(c)) moved.set(es.get(j).target());
                    }
                }
                if (moved.isEmpty()) {
                    row[c] = -1;
                    continue;
                }
                BitSet closed = nfa.epsilonClosure(moved);
                Integer target = index.get(closed);
                if (target == null) {
                    target = states.size();
                    states.add(closed);
                    index.put(closed, target);
                    work.add(target);
                }
                row[c] = target;
            }
            while (trans.size() <= d) trans.add(null);
            trans.set(d, row);
        }

        int n = states.size();
        int[][] t = new int[n][];
        int[] accept = new int[n];
        List<BitSet> tags = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            t[d] = trans.get(d);
            accept[d] = nfa.bestTag(states.get(d));
            BitSet all = new BitSet();
            BitSet set = states.get(d);
            for (int s = set.nextSetBit(0); s >= 0; s = set.nextSetBit(s + 1)) {
                if (nfa.acceptTag(s) >= 0) all.set(nfa.acceptTag(s));
            }
            tags.add(all);
        }
        return new DFA(classes, t, accept, List.copyOf(states), List.copyOf(tags));
    }
}
