package lalrgen.viz;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.grammar.Production;
import lalrgen.lr.Action;
import lalrgen.lr.Conflict;
import lalrgen.lr.LR0Item;
import lalrgen.lr.LRAutomaton;
import lalrgen.lr.LRState;
import lalrgen.lr.ParseTable;

import java.util.ArrayList;
import java.util.List;

/** Text rendering of parse tables and conflict reports. */
public final class TableFormatter {
    private TableFormatter() {}

    /** The ACTION/GOTO table with aligned columns; cells are s5, r3, acc, err or blank. */
    public static String table(ParseTable t) {
        Grammar g = t.grammar();
        List<String> headers = new ArrayList<>();
        headers.add("state");
        for (int s = 0; s < g.terminalCount(); s++) headers.add(g.name(s));
        int firstNt = g.terminalCount() + 1; // skip S'
        for (int s = firstNt; s < g.symbolCount(); s++) headers.add(g.name(s));
        List<List<String>> rows = new ArrayList<>();
        for (int st = 0; st < t.stateCount(); st++) {
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(st));
            for (int s = 0; s < g.terminalCount(); s++) {
                Action a = t.action(st, s);
                row.add(a == null ? "" : a.toString());
            }
            for (int s = firstNt; s < g.symbolCount(); s++) {
                int to = t.gotoState(st, s);
                row.add(to < 0 ? "" : String.valueOf(to));
            }
            rows.add(row);
        }
        int[] w = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            w[c] = headers.get(c).length();
            for (List<String> r : rows) w[c] = Math.max(w[c], r.get(c).length());
        }
        int gotoStart = 1 + g.terminalCount();
        StringBuilder sb = new StringBuilder();
        int actionWidth = 0;
        for (int c = 1; c < gotoStart; c++) actionWidth += w[c] + 1;
        sb.append(pad("", w[0])).append(" | ").append(pad("ACTION", actionWidth - 1)).append(" | GOTO\n");
        appendRow(sb, headers, w, gotoStart);
        StringBuilder rule = new StringBuilder();
        for (int c = 0; c < w.length; c++) {
            if (c == 1 || c == gotoStart) rule.append("-+-");
            else if (c > 0) rule.append('-');
            rule.append("-".repeat(w[c]));
        }
        sb.append(rule).append('\n');
        for (List<String> r : rows) appendRow(sb, r, w, gotoStart);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int[] w, int gotoStart) {
        for (int c = 0; c < cells.size(); c++) {
            if (c == 1 || c == gotoStart) sb.append(" | ");
            else if (c > 0) sb.append(' ');
            sb.append(pad(cells.get(c), w[c]));
        }
        sb.append('\n');
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    /** Numbered productions (so rN in the table can be looked up). */
    public static String productions(Grammar g) {
        StringBuilder sb = new StringBuilder("Productions:\n");
        for (Production p : g.productions()) {
            sb.append(String.format("  %3d  %s%n", p.id(), p.show(g)));
        }
        return sb.toString();
    }

    /**
     * Conflict report. Unresolved conflicts always; resolved ones too when verbose.
     * With expand, the example prefix is shown as tokens instead of grammar symbols.
     */
    public static String conflicts(ParseTable t, GrammarAnalysis analysis, boolean verbose, boolean expand) {
        Grammar g = t.grammar();
        LRAutomaton m = t.automaton();
        StringBuilder sb = new StringBuilder();
        for (Conflict c : t.conflicts()) {
            if (c.resolved() && !verbose) continue;
            sb.append(c.resolved() ? "Resolved conflict" : "Conflict").append(" in state ").append(c.state())
                    .append(" on lookahead ").append(g.name(c.terminal())).append(" (").append(c.kind().label()).append(")\n");
            LRState st = m.state(c.state());
            if (c.kind() == Conflict.Kind.SHIFT_REDUCE) {
                for (LR0Item it : st.items().keySet()) {
                    if (it.next(g) == c.terminal()) sb.append("  Shift:   ").append(it.show(g)).append('\n');
                }
            }
            for (int p : c.reductions()) {
                LR0Item it = new LR0Item(p, g.production(p).length());
                sb.append(p == 0 ? "  Accept:  " : "  Reduce:  ").append(it.show(g)).append('\n');
            }
            sb.append("  Example: after reading  ").append(prefix(m, c.state(), analysis, expand))
                    .append("   with next token ").append(g.name(c.terminal())).append('\n');
            sb.append("  Chosen:  ").append(describe(c.chosen(), g)).append('\n');
            sb.append("  Why:     ").append(c.reason()).append("\n\n");
        }
        return sb.toString();
    }

    /** Shortest viable prefix leading to a state, optionally expanded to terminals. */
    public static String prefix(LRAutomaton m, int state, GrammarAnalysis analysis, boolean expand) {
        Grammar g = m.grammar();
        List<Integer> path = m.shortestPrefix(state);
        if (path.isEmpty()) return "(nothing)";
        StringBuilder sb = new StringBuilder();
        for (int sym : path) {
            List<Integer> parts = expand && !g.isTerminal(sym) ? analysis.shortestExpansion(sym) : List.of(sym);
            for (int p : parts) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(g.name(p));
            }
        }
        return sb.length() == 0 ? "(nothing)" : sb.toString();
    }

    private static String describe(Action a, Grammar g) {
        return switch (a) {
            case Action.Shift s -> "shift, go to state " + s.state();
            case Action.Reduce r -> "reduce " + g.production(r.production()).show(g);
            case Action.Accept acc -> "accept";
            case Action.Error e -> "syntax error (non-associative)";
        };
    }

    /** One-line summary: states and unresolved conflict counts. */
    public static String summary(ParseTable t) {
        return String.format("%s: %d states, %d shift/reduce, %d reduce/reduce conflict(s)%s",
                t.mode().label().toUpperCase(), t.stateCount(), t.shiftReduceCount(), t.reduceReduceCount(),
                t.conflicts().stream().anyMatch(Conflict::resolved)
                        ? " (" + t.conflicts().stream().filter(Conflict::resolved).count() + " resolved by precedence)" : "");
    }
}
