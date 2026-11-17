package lalrgen.lr;

import lalrgen.grammar.Grammar;

import java.util.BitSet;
import java.util.List;

/**
 * The LR parse table: ACTION[state][terminal] and GOTO[state][non-terminal].
 * GOTO is indexed by symbol id minus the terminal count.
 */
public final class ParseTable {
    private final Mode mode;
    private final Grammar grammar;
    private final LRAutomaton automaton;
    private final Action[][] action;
    private final int[][] gotoTable;
    private final List<Conflict> conflicts;

    ParseTable(Mode mode, Grammar grammar, LRAutomaton automaton, Action[][] action, int[][] gotoTable,
               List<Conflict> conflicts) {
        this.mode = mode;
        this.grammar = grammar;
        this.automaton = automaton;
        this.action = action;
        this.gotoTable = gotoTable;
        this.conflicts = List.copyOf(conflicts);
    }

    public Mode mode() {
        return mode;
    }

    public Grammar grammar() {
        return grammar;
    }

    public LRAutomaton automaton() {
        return automaton;
    }

    public int stateCount() {
        return action.length;
    }

    /** The action for (state, terminal), or null for a syntax error. */
    public Action action(int state, int terminal) {
        return action[state][terminal];
    }

    /** GOTO[state][nonTerminal], or -1. */
    public int gotoState(int state, int nonTerminal) {
        return gotoTable[state][nonTerminal - grammar.terminalCount()];
    }

    /** Every conflict, including those resolved by precedence. */
    public List<Conflict> conflicts() {
        return conflicts;
    }

    public List<Conflict> unresolvedConflicts() {
        return conflicts.stream().filter(c -> !c.resolved()).toList();
    }

    public int shiftReduceCount() {
        return (int) conflicts.stream().filter(c -> !c.resolved() && c.kind() == Conflict.Kind.SHIFT_REDUCE).count();
    }

    public int reduceReduceCount() {
        return (int) conflicts.stream().filter(c -> !c.resolved() && c.kind() == Conflict.Kind.REDUCE_REDUCE).count();
    }

    public boolean hasUnresolvedConflicts() {
        return shiftReduceCount() + reduceReduceCount() > 0;
    }

    /** Terminals with a usable (non-error) action in a state: the "expected" set for messages. */
    public BitSet expected(int state) {
        BitSet out = new BitSet();
        for (int t = 0; t < grammar.terminalCount(); t++) {
            Action a = action[state][t];
            if (a != null && !(a instanceof Action.Error)) out.set(t);
        }
        return out;
    }
}
