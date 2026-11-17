package lalrgen.automata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DFA minimization by partition refinement.
 *
 * <p>Two states are equivalent if no input string can tell them apart. We start from a coarse
 * partition and split blocks until every block is consistent.
 *
 * <p><b>Lexer-specific detail:</b> the initial partition is one block per token type plus one
 * block for non-accepting states, not just "accepting vs non-accepting". Otherwise the accept
 * state of NUM and the accept state of ID could be merged and the lexer would report the wrong
 * token type.
 *
 * <p>{@link #hopcroft} is the O(n k log n) algorithm used by the tool. {@link #moore} is the
 * simpler O(n^2 k) algorithm, kept as an independent reference that tests compare against.
 *
 * Source: J. Hopcroft, "An n log n algorithm for minimizing states in a finite automaton" (1971);
 * Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), section 3.9.6.
 */
public final class Minimizer {
    private Minimizer() {}

    /** Hopcroft's algorithm. */
    public static DFA hopcroft(DFA d) {
        int n = d.stateCount();
        int k = d.classes().count();
        int dead = n;           // explicit dead state so the transition function is total
        int total = n + 1;
        int[][] t = totalTransitions(d);

        // inverse transitions: inv[c][target] = states that go to target on class c
        List<List<List<Integer>>> inv = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            List<List<Integer>> perTarget = new ArrayList<>();
            for (int s = 0; s < total; s++) perTarget.add(new ArrayList<>());
            for (int s = 0; s < total; s++) perTarget.get(t[s][c]).add(s);
            inv.add(perTarget);
        }

        int[] blockOf = new int[total];
        List<List<Integer>> blocks = initialPartition(d, blockOf);
        boolean[] inWork = new boolean[total + 1];
        Deque<Integer> work = new ArrayDeque<>();
        for (int b = 0; b < blocks.size(); b++) {
            work.add(b);
            inWork[b] = true;
        }

        while (!work.isEmpty()) {
            int a = work.poll();
            inWork[a] = false;
            List<Integer> splitter = new ArrayList<>(blocks.get(a));
            for (int c = 0; c < k; c++) {
                // X = states with a c-transition into the splitter
                BitSet x = new BitSet();
                for (int target : splitter) for (int s : inv.get(c).get(target)) x.set(s);
                if (x.isEmpty()) continue;
                // group the states of X by their current block
                Map<Integer, List<Integer>> hit = new LinkedHashMap<>();
                for (int s = x.nextSetBit(0); s >= 0; s = x.nextSetBit(s + 1)) {
                    hit.computeIfAbsent(blockOf[s], b -> new ArrayList<>()).add(s);
                }
                for (Map.Entry<Integer, List<Integer>> e : hit.entrySet()) {
                    int y = e.getKey();
                    List<Integer> inX = e.getValue();
                    if (inX.size() == blocks.get(y).size()) continue; // block not split
                    List<Integer> rest = new ArrayList<>();
                    for (int s : blocks.get(y)) if (!x.get(s)) rest.add(s);
                    int z = blocks.size();
                    blocks.add(inX);
                    blocks.set(y, rest);
                    for (int s : inX) blockOf[s] = z;
                    if (inWork[y]) {
                        work.add(z);
                        inWork[z] = true;
                    } else {
                        int smaller = inX.size() <= rest.size() ? z : y;
                        work.add(smaller);
                        inWork[smaller] = true;
                    }
                }
            }
        }
        return quotient(d, blockOf, dead);
    }

    /** Moore's algorithm: refine by (own block, blocks of all successors) until stable. */
    public static DFA moore(DFA d) {
        int n = d.stateCount();
        int k = d.classes().count();
        int dead = n;
        int total = n + 1;
        int[][] t = totalTransitions(d);
        int[] blockOf = new int[total];
        int count = initialPartition(d, blockOf).size();
        while (true) {
            Map<List<Integer>, Integer> ids = new HashMap<>();
            int[] next = new int[total];
            for (int s = 0; s < total; s++) {
                List<Integer> sig = new ArrayList<>(k + 1);
                sig.add(blockOf[s]);
                for (int c = 0; c < k; c++) sig.add(blockOf[t[s][c]]);
                Integer id = ids.get(sig);
                if (id == null) {
                    id = ids.size();
                    ids.put(sig, id);
                }
                next[s] = id;
            }
            blockOf = next;
            if (ids.size() == count) break;
            count = ids.size();
        }
        return quotient(d, blockOf, dead);
    }

    private static int[][] totalTransitions(DFA d) {
        int n = d.stateCount();
        int k = d.classes().count();
        int[][] t = new int[n + 1][k];
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < k; c++) {
                int to = d.transition(s, c);
                t[s][c] = to < 0 ? n : to;
            }
        }
        Arrays.fill(t[n], n);
        return t;
    }

    /** One block per accept tag, plus one block for non-accepting states (including dead). */
    private static List<List<Integer>> initialPartition(DFA d, int[] blockOf) {
        int n = d.stateCount();
        Map<Integer, Integer> blockOfTag = new LinkedHashMap<>();
        List<List<Integer>> blocks = new ArrayList<>();
        for (int s = 0; s <= n; s++) {
            int tag = s == n ? -1 : d.accept(s);
            Integer b = blockOfTag.get(tag);
            if (b == null) {
                b = blocks.size();
                blockOfTag.put(tag, b);
                blocks.add(new ArrayList<>());
            }
            blocks.get(b).add(s);
            blockOf[s] = b;
        }
        return blocks;
    }

    /**
     * Builds the minimized DFA from a partition. The block containing the dead state is dropped
     * (its transitions become -1). Blocks are renumbered in BFS order from the start block so the
     * result is deterministic.
     */
    private static DFA quotient(DFA d, int[] blockOf, int dead) {
        int k = d.classes().count();
        int deadBlock = blockOf[dead];
        int maxBlock = Arrays.stream(blockOf).max().orElse(0);
        int[] representative = new int[maxBlock + 1];
        Arrays.fill(representative, -1);
        for (int s = 0; s < d.stateCount(); s++) {
            if (representative[blockOf[s]] < 0) representative[blockOf[s]] = s;
        }
        int[] newId = new int[maxBlock + 1];
        Arrays.fill(newId, -1);
        List<Integer> order = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        int startBlock = blockOf[0];
        if (startBlock == deadBlock) {
            // The whole language is empty; keep a single non-accepting start state.
            return new DFA(d.classes(), new int[][] {filled(k)}, new int[] {-1}, null, null);
        }
        newId[startBlock] = 0;
        order.add(startBlock);
        queue.add(startBlock);
        while (!queue.isEmpty()) {
            int b = queue.poll();
            int rep = representative[b];
            for (int c = 0; c < k; c++) {
                int to = d.transition(rep, c);
                if (to < 0) continue;
                int tb = blockOf[to];
                if (tb == deadBlock || newId[tb] >= 0) continue;
                newId[tb] = order.size();
                order.add(tb);
                queue.add(tb);
            }
        }
        int m = order.size();
        int[][] trans = new int[m][k];
        int[] accept = new int[m];
        List<BitSet> tags = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            int rep = representative[order.get(i)];
            accept[i] = d.accept(rep);
            tags.add(d.allTags() == null ? new BitSet() : (BitSet) d.allTags().get(rep).clone());
            for (int c = 0; c < k; c++) {
                int to = d.transition(rep, c);
                trans[i][c] = (to < 0 || blockOf[to] == deadBlock) ? -1 : newId[blockOf[to]];
            }
        }
        return new DFA(d.classes(), trans, accept, null, List.copyOf(tags));
    }

    private static int[] filled(int k) {
        int[] r = new int[k];
        Arrays.fill(r, -1);
        return r;
    }
}
