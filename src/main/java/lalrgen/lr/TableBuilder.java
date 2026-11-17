package lalrgen.lr;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.grammar.Production;
import lalrgen.spec.Assoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fills the ACTION and GOTO tables from an automaton.
 *
 * <pre>
 * [A -> alpha . t beta] in state I, t a terminal   =>  ACTION[I][t] = shift goto(I, t)
 * [A -> alpha .]        in state I, A != S'        =>  ACTION[I][a] = reduce A -> alpha
 *        for every a in FOLLOW(A)          (SLR)
 *        for every a in the item's lookahead (LR(1) and LALR)
 * [S' -> S .]           in state I                 =>  ACTION[I][$] = accept
 * goto(I, A) = J for a non-terminal A              =>  GOTO[I][A] = J
 * </pre>
 *
 * When a cell gets more than one action, the conflict is resolved as Yacc does (see
 * {@link #resolve}) and recorded.
 *
 * Source: Aho, Lam, Sethi, Ullman, "Compilers" (2nd ed.), algorithms 4.46 and 4.56.
 */
public final class TableBuilder {
    private TableBuilder() {}

    public static ParseTable build(Grammar g, GrammarAnalysis analysis, LRAutomaton automaton, Mode mode) {
        int n = automaton.stateCount();
        int terms = g.terminalCount();
        Action[][] action = new Action[n][terms];
        int[][] gotoTable = new int[n][g.nonTerminalCount()];
        List<Conflict> conflicts = new ArrayList<>();

        for (LRState st : automaton.states()) {
            int s = st.id();
            Arrays.fill(gotoTable[s], -1);
            for (int a = terms; a < g.symbolCount(); a++) gotoTable[s][a - terms] = st.transition(a);

            // collect candidate reductions per terminal (production 0 stands for accept)
            Map<Integer, TreeSet<Integer>> reductions = new TreeMap<>();
            for (Map.Entry<LR0Item, BitSet> e : st.items().entrySet()) {
                LR0Item item = e.getKey();
                if (!item.isComplete(g)) continue;
                Production p = g.production(item.production());
                BitSet la;
                if (p.id() == 0) {
                    la = new BitSet();
                    la.set(Grammar.EOF);
                } else {
                    la = mode == Mode.SLR ? analysis.follow(p.lhs()) : e.getValue();
                }
                for (int t = la.nextSetBit(0); t >= 0; t = la.nextSetBit(t + 1)) {
                    reductions.computeIfAbsent(t, k -> new TreeSet<>()).add(p.id());
                }
            }
            for (int t = 0; t < terms; t++) {
                int shiftTo = st.transition(t);
                TreeSet<Integer> reds = reductions.get(t);
                if (reds == null) {
                    if (shiftTo >= 0) action[s][t] = new Action.Shift(shiftTo);
                    continue;
                }
                action[s][t] = resolve(g, s, t, shiftTo, new ArrayList<>(reds), conflicts);
            }
        }
        return new ParseTable(mode, g, automaton, action, gotoTable, conflicts);
    }

    /**
     * Yacc's conflict rules.
     * <ol>
     *   <li>reduce/reduce: the production listed first in the spec wins (reported).</li>
     *   <li>shift/reduce, both the token and the production have a precedence: higher precedence
     *       wins; on a tie, %left reduces, %right shifts, %nonassoc makes the cell an error
     *       (resolved silently).</li>
     *   <li>shift/reduce otherwise: shift (reported).</li>
     * </ol>
     * A production's precedence comes from its %prec, else from its rightmost terminal.
     */
    static Action resolve(Grammar g, int state, int t, int shiftTo, List<Integer> reds, List<Conflict> out) {
        int red = reds.get(0);
        Action reduce = red == 0 ? Action.ACCEPT : new Action.Reduce(red);
        if (reds.size() > 1) {
            out.add(new Conflict(state, t, Conflict.Kind.REDUCE_REDUCE, -1, List.copyOf(reds), reduce, false,
                    "the production listed first in the spec wins (rule " + red + ")"));
        }
        if (shiftTo < 0) return reduce;

        Action shift = new Action.Shift(shiftTo);
        Production p = g.production(red);
        int prodPrec = p.precLevel();
        int tokPrec = g.precedence(t);
        Action chosen;
        String reason;
        boolean resolved;
        if (prodPrec > 0 && tokPrec > 0) {
            resolved = true;
            if (prodPrec > tokPrec) {
                chosen = reduce;
                reason = "the rule's precedence (" + prodPrec + ") is higher than " + g.name(t) + " (" + tokPrec + ")";
            } else if (prodPrec < tokPrec) {
                chosen = shift;
                reason = g.name(t) + " (" + tokPrec + ") has higher precedence than the rule (" + prodPrec + ")";
            } else {
                Assoc as = g.assoc(t);
                if (as == Assoc.LEFT) {
                    chosen = reduce;
                    reason = "equal precedence and " + g.name(t) + " is %left";
                } else if (as == Assoc.RIGHT) {
                    chosen = shift;
                    reason = "equal precedence and " + g.name(t) + " is %right";
                } else {
                    chosen = Action.ERROR;
                    reason = "equal precedence and " + g.name(t) + " is %nonassoc, so this is a syntax error";
                }
            }
        } else {
            resolved = false;
            chosen = shift;
            reason = "no precedence declared, so the default is to shift";
        }
        out.add(new Conflict(state, t, Conflict.Kind.SHIFT_REDUCE, shiftTo, List.of(red), chosen, resolved, reason));
        return chosen;
    }
}
