package lalrgen.automata;

import lalrgen.regex.RegexNode;

import java.util.List;

/**
 * Thompson's construction: converts a regex AST into an NFA by gluing together small fragments,
 * one rule per AST form. Every fragment has exactly one start state and one accept state.
 *
 * <pre>
 *  CharSet c :  (s) --c--> (a)
 *  Epsilon   :  (s) --e--> (a)
 *  A B       :  A.accept --e--> B.start ; fragment = (A.start, B.accept)
 *  A | B     :  (s) --e--> A.start, B.start ; A.accept, B.accept --e--> (a)
 *  A*        :  (s) --e--> A.start, (a) ; A.accept --e--> A.start, (a)
 * </pre>
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), section 3.7.4.
 */
public final class Thompson {
    private Thompson() {}

    private record Frag(int start, int accept) {}

    /** NFA for a single regex; its accept state is tagged 0. */
    public static NFA build(RegexNode regex) {
        return buildCombined(List.of(regex));
    }

    /**
     * NFA for a list of token regexes: a new global start state with an epsilon edge to each
     * regex's fragment. The accept state of regex i is tagged i, so the tag doubles as priority.
     */
    public static NFA buildCombined(List<RegexNode> regexes) {
        NFA nfa = new NFA();
        int start = nfa.addState();
        nfa.setStart(start);
        for (int i = 0; i < regexes.size(); i++) {
            Frag f = fragment(nfa, regexes.get(i));
            nfa.addEpsilon(start, f.start());
            nfa.setAccept(f.accept(), i);
        }
        return nfa;
    }

    private static Frag fragment(NFA nfa, RegexNode node) {
        return switch (node) {
            case RegexNode.CharSet c -> {
                int s = nfa.addState();
                int a = nfa.addState();
                nfa.addEdge(s, c.set(), a);
                yield new Frag(s, a);
            }
            case RegexNode.Epsilon e -> {
                int s = nfa.addState();
                int a = nfa.addState();
                nfa.addEpsilon(s, a);
                yield new Frag(s, a);
            }
            case RegexNode.Concat c -> {
                Frag left = fragment(nfa, c.left());
                Frag right = fragment(nfa, c.right());
                nfa.addEpsilon(left.accept(), right.start());
                yield new Frag(left.start(), right.accept());
            }
            case RegexNode.Union u -> {
                int s = nfa.addState();
                Frag left = fragment(nfa, u.left());
                Frag right = fragment(nfa, u.right());
                int a = nfa.addState();
                nfa.addEpsilon(s, left.start());
                nfa.addEpsilon(s, right.start());
                nfa.addEpsilon(left.accept(), a);
                nfa.addEpsilon(right.accept(), a);
                yield new Frag(s, a);
            }
            case RegexNode.Star st -> {
                int s = nfa.addState();
                Frag inner = fragment(nfa, st.inner());
                int a = nfa.addState();
                nfa.addEpsilon(s, inner.start());
                nfa.addEpsilon(s, a);
                nfa.addEpsilon(inner.accept(), inner.start());
                nfa.addEpsilon(inner.accept(), a);
                yield new Frag(s, a);
            }
        };
    }
}
