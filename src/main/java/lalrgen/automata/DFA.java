package lalrgen.automata;

import java.util.BitSet;
import java.util.List;

/**
 * A deterministic finite automaton over character classes. State 0 is the start state.
 * {@code trans[s][c] == -1} means "no transition" (the implicit dead state).
 * {@code accept[s]} is the winning token tag of an accepting state, or -1.
 */
public final class DFA {
    private final CharClasses classes;
    private final int[][] trans;
    private final int[] accept;
    /** For a DFA built by subset construction: the NFA states each DFA state stands for. */
    private final List<BitSet> nfaStates;
    /** For each accepting state, every tag present (winner plus shadowed ones). May be null. */
    private final List<BitSet> allTags;

    public DFA(CharClasses classes, int[][] trans, int[] accept, List<BitSet> nfaStates, List<BitSet> allTags) {
        this.classes = classes;
        this.trans = trans;
        this.accept = accept;
        this.nfaStates = nfaStates;
        this.allTags = allTags;
    }

    public CharClasses classes() {
        return classes;
    }

    public int stateCount() {
        return trans.length;
    }

    public int next(int state, int cp) {
        int c = classes.classify(cp);
        return c < 0 ? -1 : trans[state][c];
    }

    public int transition(int state, int classId) {
        return trans[state][classId];
    }

    public int accept(int state) {
        return accept[state];
    }

    public List<BitSet> nfaStates() {
        return nfaStates;
    }

    public List<BitSet> allTags() {
        return allTags;
    }

    /** Runs the DFA on the whole string: returns the accept tag of the final state or -1. */
    public int run(String input) {
        int s = 0;
        for (int cp : input.codePoints().toArray()) {
            s = next(s, cp);
            if (s < 0) return -1;
        }
        return accept[s];
    }

    public int acceptingCount() {
        int n = 0;
        for (int a : accept) if (a >= 0) n++;
        return n;
    }
}
