package lalrgen.automata;

import lalrgen.regex.IntervalSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;

/**
 * A nondeterministic finite automaton with epsilon moves. Edges are labelled with character
 * sets ({@link IntervalSet}). Accepting states carry a tag: the index of the token rule they
 * recognise (lower index = higher priority).
 */
public final class NFA {
    /** A labelled edge. */
    public record Edge(IntervalSet label, int target) {}

    private final List<List<Integer>> epsilon = new ArrayList<>();
    private final List<List<Edge>> edges = new ArrayList<>();
    private final List<Integer> acceptTag = new ArrayList<>();
    private int start = -1;

    public int addState() {
        epsilon.add(new ArrayList<>());
        edges.add(new ArrayList<>());
        acceptTag.add(-1);
        return epsilon.size() - 1;
    }

    public void addEpsilon(int from, int to) {
        epsilon.get(from).add(to);
    }

    public void addEdge(int from, IntervalSet label, int to) {
        edges.get(from).add(new Edge(label, to));
    }

    public void setAccept(int state, int tag) {
        acceptTag.set(state, tag);
    }

    public void setStart(int state) {
        this.start = state;
    }

    public int start() {
        return start;
    }

    public int stateCount() {
        return epsilon.size();
    }

    public List<Integer> epsilonTargets(int state) {
        return epsilon.get(state);
    }

    public List<Edge> edges(int state) {
        return edges.get(state);
    }

    public int acceptTag(int state) {
        return acceptTag.get(state);
    }

    /** All character sets used on edges (input to alphabet compression). */
    public List<IntervalSet> labels() {
        List<IntervalSet> out = new ArrayList<>();
        for (List<Edge> es : edges) for (Edge e : es) out.add(e.label());
        return out;
    }

    /**
     * Epsilon-closure: every state reachable from the given states using only epsilon edges
     * (including the states themselves). Computed with a depth-first worklist.
     */
    public BitSet epsilonClosure(BitSet states) {
        BitSet result = (BitSet) states.clone();
        Deque<Integer> stack = new ArrayDeque<>();
        states.stream().forEach(stack::push);
        while (!stack.isEmpty()) {
            int s = stack.pop();
            for (int t : epsilon.get(s)) {
                if (!result.get(t)) {
                    result.set(t);
                    stack.push(t);
                }
            }
        }
        return result;
    }

    /** move(S, cp): states reachable from S by one edge whose label contains cp. */
    public BitSet move(BitSet states, int cp) {
        BitSet out = new BitSet();
        states.stream().forEach(s -> {
            for (Edge e : edges.get(s)) if (e.label().contains(cp)) out.set(e.target());
        });
        return out;
    }

    /**
     * Direct NFA simulation (used by tests as a reference): returns the best (lowest) accept tag
     * reached after reading the whole string, or -1 if the string is rejected.
     */
    public int simulate(String input) {
        BitSet current = new BitSet();
        current.set(start);
        current = epsilonClosure(current);
        for (int cp : input.codePoints().toArray()) {
            current = epsilonClosure(move(current, cp));
            if (current.isEmpty()) return -1;
        }
        return bestTag(current);
    }

    /** Lowest accept tag among the given states, or -1. */
    public int bestTag(BitSet states) {
        int best = -1;
        for (int s = states.nextSetBit(0); s >= 0; s = states.nextSetBit(s + 1)) {
            int t = acceptTag.get(s);
            if (t >= 0 && (best < 0 || t < best)) best = t;
        }
        return best;
    }
}
