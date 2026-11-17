package lalrgen.runtime;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.lexer.Lexer;
import lalrgen.lr.Mode;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.Pos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M4/M7/M8: the shift-reduce driver, trees, error messages, tracing and panic-mode recovery. */
class ParserTest {

    @ParameterizedTest
    @EnumSource(Mode.class)
    void treeShapeRespectsPrecedenceEncodedInGrammar(Mode mode) {
        CompiledSpec cs = TestSupport.example("expr");
        Parser.Result r = TestSupport.parse(cs, mode, "id + id * id", false);
        assertTrue(r.ok());
        assertEquals("E(E(T(F(id))), +, T(T(F(id)), *, F(id)))", r.tree().sexpr());
        assertEquals(5, r.tree().leaves().size());
    }

    @Test
    void errorListsExpectedTokens() {
        CompiledSpec cs = TestSupport.example("expr");
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "id + * id", false);
        assertFalse(r.ok());
        assertNull(r.tree());
        Diagnostic d = r.errors().get(0);
        assertEquals(new Pos(1, 6), d.pos());
        assertEquals("unexpected '*', expected one of: '(', id", d.message());
    }

    @Test
    void unexpectedEndOfInput() {
        CompiledSpec cs = TestSupport.example("expr");
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "( id + id", false);
        assertEquals("unexpected end of input, expected one of: '+', ')'", r.errors().get(0).message());
    }

    @Test
    void traceRecordsEveryStep() {
        CompiledSpec cs = TestSupport.example("expr");
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), "in", "id + id * id");
        Parser.Result r = Parser.parse(cs.table(Mode.SLR), lex.tokens(), Parser.Options.defaults().withTrace(true), "in");
        assertEquals(14, r.trace().size()); // 5 shifts + 8 reductions + accept
        assertEquals("shift 2", r.trace().get(0).action());
        assertEquals("reduce F -> id", r.trace().get(1).action());
        assertEquals("0", r.trace().get(0).stack());
        assertEquals("accept", r.trace().get(13).action());
        assertEquals(5, r.trace().stream().filter(s -> s.action().startsWith("shift")).count());
    }

    @Test
    void panicModeRecoveryReportsSeveralErrors() {
        CompiledSpec cs = TestSupport.example("calc");
        String input = "x = 1 +;\ny = 2;\nz = ) 3;\nw = 4;\n";
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), "in", input);
        Parser.Result r = Parser.parse(cs.table(Mode.LALR), lex.tokens(),
                Parser.Options.defaults().withRecover(true), "in");
        assertFalse(r.ok());
        assertNull(r.tree());
        assertEquals(List.of(new Pos(1, 8), new Pos(3, 5)), r.errors().stream().map(Diagnostic::pos).toList());
        assertTrue(r.accepted(), "after recovery the rest of the input still parses");
    }

    @Test
    void recoveryWithoutSyncTokenGivesUpCleanly() {
        CompiledSpec cs = TestSupport.example("expr"); // no ';' or '}' in this grammar
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), "in", "id + + id ) id");
        Parser.Result r = Parser.parse(cs.table(Mode.LALR), lex.tokens(),
                Parser.Options.defaults().withRecover(true), "in");
        assertFalse(r.ok());
        assertTrue(r.errors().size() >= 1 && r.errors().size() <= 3, r.errors().toString());
    }

    @Test
    void recoveryNeverLoops() {
        CompiledSpec cs = TestSupport.example("minic");
        String garbage = "int ;;; } { ) ( int int = = ; } } return ; else if while ( ; ) ".repeat(30);
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), "in", garbage);
        Parser.Result r = Parser.parse(cs.table(Mode.LALR), lex.tokens(),
                Parser.Options.defaults().withRecover(true), "in");
        assertFalse(r.ok());
        assertTrue(r.errors().size() <= Parser.DEFAULT_MAX_ERRORS + 1);
    }

    @Test
    void epsilonProductionsBuildEmptyNodes() {
        CompiledSpec cs = TestSupport.example("minic");
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "", false);
        assertTrue(r.ok());
        assertEquals("program(decls())", r.tree().sexpr());
    }
}
