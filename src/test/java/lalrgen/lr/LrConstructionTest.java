package lalrgen.lr;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.grammar.Grammar;
import lalrgen.viz.TableFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4/M5: LR(0), SLR, LR(1) and LALR(1) on the textbook grammars, with known state counts. */
class LrConstructionTest {

    @Test
    void expressionGrammarHas12Lr0StatesAndIsSlr() {
        CompiledSpec cs = TestSupport.example("expr");
        assertEquals(12, cs.lr0().stateCount());
        ParseTable slr = cs.table(Mode.SLR);
        assertTrue(slr.conflicts().isEmpty());
        assertEquals(22, cs.lr1().stateCount(), "canonical LR(1) collection of the expression grammar");
        assertEquals(12, cs.table(Mode.LALR).stateCount());
    }

    @Test
    void ccGrammarHas10Lr1And7LalrStates() {
        CompiledSpec cs = TestSupport.example("cc");
        assertEquals(10, cs.lr1().stateCount());
        assertEquals(7, cs.table(Mode.LALR).stateCount());
        assertEquals(7, cs.lr0().stateCount());
        // the three merged pairs of the dragon book (I3/I6, I4/I7, I8/I9)
        long merged = lalrgen.lr.LalrMerger.mergedFrom(cs.lr1()).stream().filter(g -> g.size() == 2).count();
        assertEquals(3, merged);
    }

    @Test
    void lr1LookaheadsInCcGrammar() {
        CompiledSpec cs = TestSupport.example("cc");
        Grammar g = cs.grammar();
        LRAutomaton lr1 = cs.lr1();
        // state 0: [S' -> .S, $], [S -> .C C, $], [C -> .c C, c/d], [C -> .d, c/d]
        LRState s0 = lr1.state(0);
        assertEquals(4, s0.itemCount());
        int cProd = g.productionsOf(g.idOf("C")).get(0).id();
        assertEquals("'c', 'd'", lr1.names(s0.lookahead(new LR0Item(cProd, 0))));
        assertEquals("$", lr1.names(s0.lookahead(new LR0Item(1, 0))));
    }

    @Test
    void notSlrGrammar() {
        CompiledSpec cs = TestSupport.example("not_slr");
        ParseTable slr = cs.table(Mode.SLR);
        assertEquals(1, slr.shiftReduceCount());
        Conflict c = slr.unresolvedConflicts().get(0);
        assertEquals("'='", cs.grammar().name(c.terminal()));
        assertEquals("R -> L", cs.grammar().production(c.reductions().get(0)).show(cs.grammar()));
        assertTrue(cs.table(Mode.LR1).conflicts().isEmpty());
        assertTrue(cs.table(Mode.LALR).conflicts().isEmpty());
    }

    @Test
    void notLalrGrammarGetsReduceReduceOnlyAfterMerging() {
        CompiledSpec cs = TestSupport.example("not_lalr");
        assertTrue(cs.table(Mode.LR1).conflicts().isEmpty());
        ParseTable lalr = cs.table(Mode.LALR);
        assertEquals(0, lalr.shiftReduceCount());
        assertEquals(2, lalr.reduceReduceCount()); // on 'd' and on 'e'
        assertEquals(cs.lr1().stateCount() - 1, lalr.stateCount());
        Conflict c = lalr.unresolvedConflicts().get(0);
        assertEquals(Conflict.Kind.REDUCE_REDUCE, c.kind());
        assertEquals(List.of("A -> 'c'", "B -> 'c'"),
                c.reductions().stream().map(p -> cs.grammar().production(p).show(cs.grammar())).toList());
        // the production listed first wins
        assertEquals(new Action.Reduce(c.reductions().get(0)), c.chosen());
    }

    @Test
    void conflictReportShowsShortestPrefix() {
        CompiledSpec cs = TestSupport.example("dangling_else");
        ParseTable t = cs.table(Mode.LALR);
        assertEquals(1, t.shiftReduceCount());
        String report = TableFormatter.conflicts(t, cs.analysis(), false, false);
        assertTrue(report.contains("on lookahead 'else' (shift/reduce)"), report);
        assertTrue(report.contains("Shift:   stmt -> 'if' ID 'then' stmt . 'else' stmt"), report);
        assertTrue(report.contains("Reduce:  stmt -> 'if' ID 'then' stmt ."), report);
        assertTrue(report.contains("Example: after reading  'if' ID 'then' stmt   with next token 'else'"), report);
        assertTrue(report.contains("Chosen:  shift"), report);
        assertTrue(report.contains("Why:     no precedence declared"), report);
        String expanded = TableFormatter.conflicts(t, cs.analysis(), false, true);
        assertTrue(expanded.contains("after reading  'if' ID 'then' ID   with"), expanded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expr", "cc", "not_slr", "not_lalr", "calc", "dangling_else", "dangling_else_prec",
            "nonassoc", "json", "minic"})
    void lalrHasExactlyAsManyStatesAsLr0(String name) {
        CompiledSpec cs = TestSupport.example(name);
        assertEquals(cs.lr0().stateCount(), cs.table(Mode.LALR).stateCount());
        assertTrue(cs.lr1().stateCount() >= cs.lr0().stateCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expr", "cc", "not_slr", "not_lalr", "calc", "dangling_else", "dangling_else_prec",
            "nonassoc", "json", "minic"})
    void mergingNeverAddsShiftReduceConflicts(String name) {
        CompiledSpec cs = TestSupport.example(name);
        assertTrue(cs.table(Mode.LALR).shiftReduceCount() <= cs.table(Mode.LR1).shiftReduceCount());
    }

    @Test
    void acceptIsOnEndMarkerOnly() {
        CompiledSpec cs = TestSupport.example("expr");
        ParseTable t = cs.table(Mode.LALR);
        int acceptState = cs.lr0().state(0).transition(cs.grammar().idOf("E"));
        assertInstanceOf(Action.Accept.class, t.action(acceptState, Grammar.EOF));
        assertInstanceOf(Action.Shift.class, t.action(acceptState, cs.grammar().idOf("'+'")));
    }

    @Test
    void deterministicNumbering() {
        CompiledSpec a = TestSupport.example("minic");
        CompiledSpec b = TestSupport.example("minic");
        assertEquals(TableFormatter.table(a.table(Mode.LALR)), TableFormatter.table(b.table(Mode.LALR)));
    }
}
