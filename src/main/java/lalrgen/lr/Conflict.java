package lalrgen.lr;

import java.util.List;

/**
 * A table cell that received more than one action.
 *
 * @param state      automaton state
 * @param terminal   lookahead terminal
 * @param kind       shift/reduce or reduce/reduce
 * @param shiftTo    shift target state (-1 for reduce/reduce)
 * @param reductions the competing productions (ascending; 0 means accept)
 * @param chosen     the action that was put in the table
 * @param resolved   true if precedence/associativity settled it (not reported as a warning)
 * @param reason     human-readable explanation of the choice
 */
public record Conflict(int state, int terminal, Kind kind, int shiftTo, List<Integer> reductions,
                       Action chosen, boolean resolved, String reason) {

    public enum Kind {
        SHIFT_REDUCE("shift/reduce"), REDUCE_REDUCE("reduce/reduce");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
