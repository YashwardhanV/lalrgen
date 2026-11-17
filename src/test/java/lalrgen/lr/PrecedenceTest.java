package lalrgen.lr;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.runtime.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M6: precedence and associativity resolution, Yacc style. */
class PrecedenceTest {

    @Test
    void calcHasNoUnresolvedConflicts() {
        CompiledSpec cs = TestSupport.example("calc");
        for (Mode m : Mode.values()) {
            ParseTable t = cs.table(m);
            assertFalse(t.hasUnresolvedConflicts(), m + ": " + t.unresolvedConflicts());
            assertTrue(t.conflicts().size() > 0, "the ambiguity is real; precedence resolves it");
        }
    }

    @Test
    void leftAssociativity() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Print(Sub(Sub(Num(1), Num(2)), Num(3)))))", TestSupport.ast(cs, "1 - 2 - 3;"));
    }

    @Test
    void rightAssociativity() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Print(Pow(Num(2), Pow(Num(3), Num(2))))))", TestSupport.ast(cs, "2 ^ 3 ^ 2;"));
    }

    @Test
    void precedenceLevels() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Print(Add(Num(1), Mul(Num(2), Num(3))))))", TestSupport.ast(cs, "1 + 2 * 3;"));
        assertEquals("Program(List(Print(Add(Mul(Num(1), Num(2)), Num(3)))))", TestSupport.ast(cs, "1 * 2 + 3;"));
    }

    @Test
    void precOverridesRightmostTerminal() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Print(Mul(Neg(Num(2)), Num(3)))))", TestSupport.ast(cs, "-2 * 3;"));
        assertEquals("Program(List(Print(Sub(Num(1), Neg(Num(2))))))", TestSupport.ast(cs, "1 - -2;"));
        // UMINUS is below '^', so -2^2 is -(2^2)
        assertEquals("Program(List(Print(Neg(Pow(Num(2), Num(2))))))", TestSupport.ast(cs, "-2 ^ 2;"));
    }

    @Test
    void danglingElseShiftsByDefault() {
        CompiledSpec cs = TestSupport.example("dangling_else");
        ParseTable t = cs.table(Mode.LALR);
        assertEquals(1, t.shiftReduceCount());
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "if a then if b then x else y", false);
        assertTrue(r.ok());
        // outer stmt has 4 children (no else); the inner one has 6 (with else)
        assertEquals(4, r.tree().children().size());
        assertEquals(6, r.tree().children().get(3).children().size());
    }

    @Test
    void danglingElseWithPrecIsSilent() {
        CompiledSpec cs = TestSupport.example("dangling_else_prec");
        assertTrue(cs.table(Mode.LALR).unresolvedConflicts().isEmpty());
        assertEquals("If(a, IfElse(b, Stmt(x), Stmt(y)))", TestSupport.ast(cs, "if a then if b then x else y"));
    }

    @Test
    void nonAssociativeOperatorsCannotChain() {
        CompiledSpec cs = TestSupport.example("nonassoc");
        assertTrue(TestSupport.accepts(cs, Mode.LALR, "a < b"));
        assertTrue(TestSupport.accepts(cs, Mode.LALR, "a < b + c"));
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "a < b < c", false);
        assertFalse(r.ok());
        assertTrue(r.errors().get(0).message().contains("non-associative"), r.errors().toString());
        assertEquals(7, r.errors().get(0).pos().col()); // the second '<'
    }

    @Test
    void resolvedConflictsAreRecordedWithReasons() {
        CompiledSpec cs = TestSupport.example("calc");
        ParseTable t = cs.table(Mode.LALR);
        assertTrue(t.conflicts().stream().allMatch(Conflict::resolved));
        assertTrue(t.conflicts().stream().anyMatch(c -> c.reason().contains("%left")));
        assertTrue(t.conflicts().stream().anyMatch(c -> c.reason().contains("%right")));
        assertTrue(t.conflicts().stream().anyMatch(c -> c.reason().contains("higher")));
    }
}
