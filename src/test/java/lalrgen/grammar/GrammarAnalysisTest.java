package lalrgen.grammar;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4: nullable, FIRST, FOLLOW (checked against the sets computed by hand in the dragon book). */
class GrammarAnalysisTest {

    /** Dragon book grammar 4.28: the LL(1) expression grammar with epsilon rules. */
    static final String LL1_EXPR = """
            %token id /[a-z]+/
            %%
            E  : T Ep ;
            Ep : '+' T Ep | %empty ;
            T  : F Tp ;
            Tp : '*' F Tp | %empty ;
            F  : '(' E ')' | id ;
            """;

    private static String first(CompiledSpec cs, String nt) {
        return cs.analysis().format(cs.analysis().first(cs.grammar().idOf(nt)));
    }

    private static String follow(CompiledSpec cs, String nt) {
        return cs.analysis().format(cs.analysis().follow(cs.grammar().idOf(nt)));
    }

    @Test
    void dragonBookFirstAndFollow() {
        CompiledSpec cs = TestSupport.compile(LL1_EXPR);
        GrammarAnalysis a = cs.analysis();
        Grammar g = cs.grammar();
        assertTrue(a.nullable(g.idOf("Ep")));
        assertTrue(a.nullable(g.idOf("Tp")));
        assertFalse(a.nullable(g.idOf("E")));
        assertEquals("{ '(', id }", first(cs, "E"));
        assertEquals("{ '(', id }", first(cs, "T"));
        assertEquals("{ '(', id }", first(cs, "F"));
        assertEquals("{ '+' }", first(cs, "Ep"));
        assertEquals("{ '*' }", first(cs, "Tp"));
        assertEquals("{ $, ')' }", follow(cs, "E"));
        assertEquals("{ $, ')' }", follow(cs, "Ep"));
        assertEquals("{ $, '+', ')' }", follow(cs, "T"));
        assertEquals("{ $, '+', ')' }", follow(cs, "Tp"));
        assertEquals("{ $, '+', '*', ')' }", follow(cs, "F"));
    }

    @Test
    void lrExpressionGrammarFollow() {
        CompiledSpec cs = TestSupport.example("expr");
        assertEquals("{ $, '+', ')' }", follow(cs, "E"));
        assertEquals("{ $, '+', '*', ')' }", follow(cs, "T"));
    }

    @Test
    void firstOfSequenceSkipsNullablePrefix() {
        CompiledSpec cs = TestSupport.compile("%%\ns : a b 'z' ;\na : 'x' | %empty ;\nb : 'y' | %empty ;\n");
        Grammar g = cs.grammar();
        int[] seq = {g.idOf("a"), g.idOf("b"), g.idOf("'z'")};
        assertEquals("{ 'z', 'x', 'y' }", cs.analysis().format(cs.analysis().firstOf(seq, 0)));
        assertFalse(cs.analysis().isNullable(seq, 0));
        assertFalse(cs.analysis().isNullable(seq, 2));
        assertTrue(cs.analysis().isNullable(new int[] {g.idOf("a"), g.idOf("b")}, 0));
    }

    @Test
    void shortestExpansion() {
        CompiledSpec cs = TestSupport.example("expr");
        Grammar g = cs.grammar();
        List<Integer> e = cs.analysis().shortestExpansion(g.idOf("E"));
        assertEquals(List.of(g.idOf("id")), e);
    }
}
