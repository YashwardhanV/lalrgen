package lalrgen.lr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * One state of an LR automaton: a closed set of items. For LR(1) and LALR states each item maps
 * to its lookahead set; for LR(0) states the lookahead sets are empty.
 */
public final class LRState {
    private final int id;
    private final SortedMap<LR0Item, BitSet> items;
    private final int[] transitions;

    LRState(int id, SortedMap<LR0Item, BitSet> items, int symbolCount) {
        this.id = id;
        this.items = Collections.unmodifiableSortedMap(items);
        this.transitions = new int[symbolCount];
        Arrays.fill(transitions, -1);
    }

    public int id() {
        return id;
    }

    /** All items (kernel plus closure) with their lookaheads. Do not modify the bit sets. */
    public SortedMap<LR0Item, BitSet> items() {
        return items;
    }

    public List<LR0Item> kernel() {
        List<LR0Item> k = new ArrayList<>();
        for (LR0Item it : items.keySet()) if (it.isKernel()) k.add(it);
        return k;
    }

    /** The goto/shift target on a symbol, or -1. */
    public int transition(int symbol) {
        return transitions[symbol];
    }

    void setTransition(int symbol, int target) {
        transitions[symbol] = target;
    }

    public BitSet lookahead(LR0Item item) {
        return items.get(item);
    }

    public int itemCount() {
        return items.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("State ").append(id);
        for (Map.Entry<LR0Item, BitSet> e : items.entrySet()) sb.append("\n  ").append(e.getKey()).append(' ').append(e.getValue());
        return sb.toString();
    }
}
