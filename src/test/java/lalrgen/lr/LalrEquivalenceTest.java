package lalrgen.lr;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5 property test: on conflict-free grammars, the LALR table accepts exactly the same token
 * strings as the canonical LR(1) table (and as SLR, when SLR is conflict-free too). Inputs are
 * random sentences of the grammar, random one-token edits of them, and random token strings.
 */
class LalrEquivalenceTest {

    @ParameterizedTest
    @ValueSource(strings = {"expr", "cc", "not_slr", "calc", "dangling_else_prec", "nonassoc", "json", "minic"})
    void lalrAcceptsTheSameLanguageAsLr1(String name) {
        CompiledSpec cs = TestSupport.example(name);
        ParseTable lr1 = cs.table(Mode.LR1);
        ParseTable lalr = cs.table(Mode.LALR);
        ParseTable slr = cs.table(Mode.SLR);
        assertTrue(!lalr.hasUnresolvedConflicts() && !lr1.hasUnresolvedConflicts());
        boolean checkSlr = !slr.hasUnresolvedConflicts();
        Random rnd = new Random(name.hashCode());
        int terms = cs.grammar().terminalCount();
        int accepted = 0;
        int rejected = 0;
        for (int k = 0; k < 400; k++) {
            List<Integer> s;
            if (k % 3 == 0) {
                s = TestSupport.randomSentence(cs, rnd, 6);
            } else if (k % 3 == 1) {
                s = TestSupport.mutate(TestSupport.randomSentence(cs, rnd, 6), terms, rnd);
            } else {
                s = new ArrayList<>();
                int len = rnd.nextInt(8);
                for (int i = 0; i < len; i++) s.add(1 + rnd.nextInt(terms - 1));
            }
            boolean byLr1 = TestSupport.accepts(lr1, cs, s);
            assertEquals(byLr1, TestSupport.accepts(lalr, cs, s), name + ": LALR and LR(1) disagree on " + names(cs, s));
            if (checkSlr) assertEquals(byLr1, TestSupport.accepts(slr, cs, s), name + ": SLR disagrees on " + names(cs, s));
            // with precedence, an ambiguous grammar's parser accepts only a subset (e.g. %nonassoc)
            if (k % 3 == 0 && lr1.conflicts().isEmpty()) assertTrue(byLr1, name + ": generated sentence rejected: " + names(cs, s));
            if (byLr1) accepted++;
            else rejected++;
        }
        assertTrue(accepted > 50 && rejected > 50, "test inputs should exercise both outcomes");
    }

    private static String names(CompiledSpec cs, List<Integer> s) {
        return s.stream().map(cs.grammar()::name).toList().toString();
    }
}
