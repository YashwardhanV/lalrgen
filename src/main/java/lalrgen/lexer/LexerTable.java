package lalrgen.lexer;

import lalrgen.automata.CharClasses;
import lalrgen.automata.DFA;
import lalrgen.automata.Minimizer;
import lalrgen.automata.NFA;
import lalrgen.automata.SubsetConstruction;
import lalrgen.automata.Thompson;
import lalrgen.regex.RegexNode;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The generated lexer: every stage of the pipeline is kept so it can be printed or drawn.
 * regex rules, then Thompson NFA, then subset-construction DFA, then minimized DFA.
 */
public final class LexerTable {
    private final List<TokenRule> rules;
    private final NFA nfa;
    private final DFA dfa;
    private final DFA minDfa;

    private LexerTable(List<TokenRule> rules, NFA nfa, DFA dfa, DFA minDfa) {
        this.rules = rules;
        this.nfa = nfa;
        this.dfa = dfa;
        this.minDfa = minDfa;
    }

    /** Runs the whole lexer-generator pipeline. Rule i must have index i. */
    public static LexerTable generate(List<TokenRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).index() != i) throw new IllegalArgumentException("rule index mismatch at " + i);
        }
        List<RegexNode> regexes = new ArrayList<>();
        for (TokenRule r : rules) regexes.add(r.regex());
        NFA nfa = Thompson.buildCombined(regexes);
        CharClasses classes = CharClasses.build(nfa.labels());
        DFA dfa = SubsetConstruction.build(nfa, classes);
        DFA min = Minimizer.hopcroft(dfa);
        return new LexerTable(List.copyOf(rules), nfa, dfa, min);
    }

    public List<TokenRule> rules() {
        return rules;
    }

    public NFA nfa() {
        return nfa;
    }

    public DFA dfa() {
        return dfa;
    }

    /** The DFA used at run time. */
    public DFA minDfa() {
        return minDfa;
    }

    /**
     * Rules that can never win: every string they match is also matched by a rule with higher
     * priority. Returns pairs {shadowed rule, one rule that beats it}.
     */
    public List<int[]> shadowedRules() {
        BitSet winners = new BitSet();
        int n = dfa.stateCount();
        for (int s = 0; s < n; s++) if (dfa.accept(s) >= 0) winners.set(dfa.accept(s));
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < rules.size(); r++) {
            if (winners.get(r)) continue;
            for (int s = 0; s < n; s++) {
                if (dfa.allTags().get(s).get(r)) {
                    out.add(new int[] {r, dfa.accept(s)});
                    break;
                }
            }
        }
        return out;
    }
}
