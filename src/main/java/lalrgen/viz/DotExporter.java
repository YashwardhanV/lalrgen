package lalrgen.viz;

import lalrgen.automata.DFA;
import lalrgen.automata.NFA;
import lalrgen.grammar.Grammar;
import lalrgen.lexer.TokenRule;
import lalrgen.lr.Conflict;
import lalrgen.lr.LR0Item;
import lalrgen.lr.LRAutomaton;
import lalrgen.lr.LRState;
import lalrgen.lr.ParseTable;
import lalrgen.regex.IntervalSet;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Graphviz DOT output. Render with, for example: {@code dot -Tsvg nfa.dot -o nfa.svg}.
 */
public final class DotExporter {
    private DotExporter() {}

    /** Thompson NFA: epsilon edges dashed, accept states double circles labelled with the token. */
    public static String nfa(NFA nfa, List<TokenRule> rules) {
        StringBuilder sb = header("NFA");
        for (int s = 0; s < nfa.stateCount(); s++) {
            int tag = nfa.acceptTag(s);
            if (tag >= 0) {
                sb.append("  ").append(s).append(" [shape=doublecircle, label=\"").append(s).append("\\n")
                        .append(esc(rules.get(tag).name())).append("\"];\n");
            }
        }
        sb.append("  __start [shape=point]; __start -> ").append(nfa.start()).append(";\n");
        for (int s = 0; s < nfa.stateCount(); s++) {
            for (int t : nfa.epsilonTargets(s)) {
                sb.append("  ").append(s).append(" -> ").append(t).append(" [style=dashed, label=\"ε\"];\n");
            }
            Map<Integer, IntervalSet> byTarget = new TreeMap<>();
            for (NFA.Edge e : nfa.edges(s)) byTarget.merge(e.target(), e.label(), IntervalSet::union);
            for (Map.Entry<Integer, IntervalSet> e : byTarget.entrySet()) {
                sb.append("  ").append(s).append(" -> ").append(e.getKey()).append(" [label=\"")
                        .append(esc(e.getValue().toString())).append("\"];\n");
            }
        }
        return sb.append("}\n").toString();
    }

    /**
     * DFA (subset construction or minimized). Parallel edges are merged into one label. For the
     * subset-construction DFA, each state also lists the NFA states it stands for.
     */
    public static String dfa(DFA dfa, List<TokenRule> rules, String name) {
        StringBuilder sb = header(name);
        sb.append("  __start [shape=point]; __start -> 0;\n");
        for (int s = 0; s < dfa.stateCount(); s++) {
            StringBuilder label = new StringBuilder(String.valueOf(s));
            if (dfa.nfaStates() != null) label.append("\\n").append(shortSet(dfa.nfaStates().get(s)));
            if (dfa.accept(s) >= 0) label.append("\\n").append(esc(rules.get(dfa.accept(s)).name()));
            String shape = dfa.nfaStates() != null
                    ? (dfa.accept(s) >= 0 ? "shape=ellipse, peripheries=2, " : "shape=ellipse, ")
                    : (dfa.accept(s) >= 0 ? "shape=doublecircle, " : "");
            sb.append("  ").append(s).append(" [").append(shape).append("label=\"").append(label).append("\"];\n");
        }
        for (int s = 0; s < dfa.stateCount(); s++) {
            Map<Integer, IntervalSet> byTarget = new TreeMap<>();
            for (int c = 0; c < dfa.classes().count(); c++) {
                int t = dfa.transition(s, c);
                if (t >= 0) byTarget.merge(t, dfa.classes().members(c), IntervalSet::union);
            }
            for (Map.Entry<Integer, IntervalSet> e : byTarget.entrySet()) {
                sb.append("  ").append(s).append(" -> ").append(e.getKey()).append(" [label=\"")
                        .append(esc(e.getValue().toString())).append("\"];\n");
            }
        }
        return sb.append("}\n").toString();
    }

    /**
     * LR automaton: each node lists its items (with lookaheads for LR(1)/LALR). States with an
     * unresolved conflict in the given table are outlined in red.
     */
    public static String lr(LRAutomaton m, ParseTable table) {
        Grammar g = m.grammar();
        Set<Integer> conflictStates = new TreeSet<>();
        if (table != null) for (Conflict c : table.unresolvedConflicts()) conflictStates.add(c.state());
        StringBuilder sb = new StringBuilder("digraph " + m.kind() + " {\n  rankdir=LR;\n  node [shape=box, fontname=\"monospace\", fontsize=10];\n");
        for (LRState st : m.states()) {
            StringBuilder label = new StringBuilder("I").append(st.id()).append("\\l");
            for (Map.Entry<LR0Item, BitSet> e : LRAutomaton.kernelFirst(st)) {
                label.append(esc(e.getKey().show(g)));
                if (m.hasLookaheads()) {
                    label.append(" , ");
                    StringBuilder la = new StringBuilder();
                    BitSet b = e.getValue();
                    for (int t = b.nextSetBit(0); t >= 0; t = b.nextSetBit(t + 1)) {
                        if (la.length() > 0) la.append('/');
                        la.append(g.name(t));
                    }
                    label.append(esc(la.toString()));
                }
                label.append("\\l");
            }
            sb.append("  ").append(st.id()).append(" [label=\"").append(label).append('"');
            if (conflictStates.contains(st.id())) sb.append(", color=red, penwidth=2");
            sb.append("];\n");
        }
        for (LRState st : m.states()) {
            for (int sym = 0; sym < g.symbolCount(); sym++) {
                int t = st.transition(sym);
                if (t >= 0) {
                    sb.append("  ").append(st.id()).append(" -> ").append(t).append(" [label=\"")
                            .append(esc(g.name(sym))).append("\"];\n");
                }
            }
        }
        return sb.append("}\n").toString();
    }

    private static StringBuilder header(String name) {
        return new StringBuilder("digraph " + name + " {\n  rankdir=LR;\n  node [shape=circle, fontsize=10];\n  edge [fontsize=10];\n");
    }

    private static String shortSet(BitSet b) {
        String s = b.toString();
        return s.length() > 40 ? s.substring(0, 37) + "...}" : s;
    }

    /** Escapes text for use inside a double-quoted DOT label. */
    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
