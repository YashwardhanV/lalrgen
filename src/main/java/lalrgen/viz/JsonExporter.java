package lalrgen.viz;

import lalrgen.automata.DFA;
import lalrgen.core.CompiledSpec;
import lalrgen.grammar.Grammar;
import lalrgen.grammar.Production;
import lalrgen.lexer.TokenRule;
import lalrgen.lr.Action;
import lalrgen.lr.ParseTable;

import java.util.List;

/**
 * Writes everything needed to run the lexer and parser without the spec file: symbols,
 * productions, the minimized lexer DFA and the ACTION/GOTO tables.
 */
public final class JsonExporter {
    private JsonExporter() {}

    public static String export(CompiledSpec cs, ParseTable t) {
        Grammar g = cs.grammar();
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"mode\": ").append(Json.str(t.mode().label())).append(",\n");

        sb.append("  \"terminals\": [");
        for (int s = 0; s < g.terminalCount(); s++) sb.append(s == 0 ? "" : ", ").append(Json.str(g.name(s)));
        sb.append("],\n  \"nonterminals\": [");
        for (int s = g.terminalCount(); s < g.symbolCount(); s++) {
            sb.append(s == g.terminalCount() ? "" : ", ").append(Json.str(g.name(s)));
        }
        sb.append("],\n");

        sb.append("  \"productions\": [\n");
        for (Production p : g.productions()) {
            sb.append("    {\"id\": ").append(p.id()).append(", \"lhs\": ").append(p.lhs())
                    .append(", \"rhs\": ").append(intList(p.rhs())).append(", \"text\": ")
                    .append(Json.str(p.show(g)));
            if (p.ast() != null) sb.append(", \"ast\": ").append(Json.str(p.ast().toString()));
            sb.append(p.id() + 1 < g.productions().size() ? "},\n" : "}\n");
        }
        sb.append("  ],\n");

        DFA dfa = cs.lexer().minDfa();
        List<TokenRule> rules = cs.lexer().rules();
        sb.append("  \"lexer\": {\n    \"rules\": [\n");
        for (TokenRule r : rules) {
            sb.append("      {\"index\": ").append(r.index()).append(", \"name\": ").append(Json.str(r.name()))
                    .append(", \"kind\": ").append(Json.str(r.kind().name().toLowerCase()))
                    .append(", \"terminal\": ").append(g.terminalOfRule(r.index()))
                    .append(r.index() + 1 < rules.size() ? "},\n" : "}\n");
        }
        sb.append("    ],\n    \"classes\": [");
        List<int[]> iv = dfa.classes().intervalTable();
        for (int k = 0; k < iv.size(); k++) {
            int[] e = iv.get(k);
            sb.append(k == 0 ? "" : ", ").append('[').append(e[0]).append(", ").append(e[1]).append(", ").append(e[2]).append(']');
        }
        sb.append("],\n    \"start\": 0,\n    \"accept\": [");
        for (int s = 0; s < dfa.stateCount(); s++) sb.append(s == 0 ? "" : ", ").append(dfa.accept(s));
        sb.append("],\n    \"transitions\": [\n");
        for (int s = 0; s < dfa.stateCount(); s++) {
            int[] row = new int[dfa.classes().count()];
            for (int c = 0; c < row.length; c++) row[c] = dfa.transition(s, c);
            sb.append("      ").append(intList(row)).append(s + 1 < dfa.stateCount() ? ",\n" : "\n");
        }
        sb.append("    ]\n  },\n");

        sb.append("  \"action\": [\n");
        for (int s = 0; s < t.stateCount(); s++) {
            sb.append("    [");
            for (int a = 0; a < g.terminalCount(); a++) {
                Action act = t.action(s, a);
                sb.append(a == 0 ? "" : ", ").append(Json.str(act == null ? "" : act.toString()));
            }
            sb.append(s + 1 < t.stateCount() ? "],\n" : "]\n");
        }
        sb.append("  ],\n  \"goto\": [\n");
        for (int s = 0; s < t.stateCount(); s++) {
            int[] row = new int[g.nonTerminalCount()];
            for (int a = 0; a < row.length; a++) row[a] = t.gotoState(s, a + g.terminalCount());
            sb.append("    ").append(intList(row)).append(s + 1 < t.stateCount() ? ",\n" : "\n");
        }
        sb.append("  ],\n  \"conflicts\": {\"shift_reduce\": ").append(t.shiftReduceCount())
                .append(", \"reduce_reduce\": ").append(t.reduceReduceCount()).append("}\n}\n");
        return sb.toString();
    }

    private static String intList(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) sb.append(i == 0 ? "" : ", ").append(a[i]);
        return sb.append(']').toString();
    }
}
