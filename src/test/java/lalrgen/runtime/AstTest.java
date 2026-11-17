package lalrgen.runtime;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.lr.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M7: AST annotations, including List flattening. */
class AstTest {

    @Test
    void passThroughAndConstructors() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Assign(Var(x), Mul(Add(Num(1), Num(2)), Num(3)))))",
                TestSupport.ast(cs, "x = (1 + 2) * 3;"));
    }

    @Test
    void leftRecursiveListsAreFlattened() {
        CompiledSpec cs = TestSupport.example("calc");
        assertEquals("Program(List(Print(Num(1)), Print(Num(2)), Print(Num(3)), Print(Num(4))))",
                TestSupport.ast(cs, "1; 2; 3; 4;"));
    }

    @Test
    void json() {
        CompiledSpec cs = TestSupport.example("json");
        assertEquals("Object(List(Pair(Str(\"a\"), Array(List(Num(1), True(), Null()))), Pair(Str(\"b\"), Object())))",
                TestSupport.ast(cs, "{\"a\": [1, true, null], \"b\": {}}"));
        assertEquals("Array()", TestSupport.ast(cs, "[]"));
    }

    @Test
    void minicDanglingElseAndPrecedence() {
        CompiledSpec cs = TestSupport.example("minic");
        String ast = TestSupport.ast(cs, "void f(void) { if (a) if (b) x = 1; else x = 2; }");
        assertTrue(ast.contains("If(Var(a), If(Var(b), ExprStmt(Assign(x, Int(1))), ExprStmt(Assign(x, Int(2)))))"), ast);
        String e = TestSupport.ast(cs, "int g() { return !a || b && c == d + e * -f; }");
        assertTrue(e.contains("Return(Or(Not(Var(a)), And(Var(b), Eq(Var(c), Add(Var(d), Mul(Var(e), Neg(Var(f))))))))"), e);
        String chain = TestSupport.ast(cs, "int g() { a = b = c; }");
        assertTrue(chain.contains("Assign(a, Assign(b, Var(c)))"), chain);
    }

    @Test
    void treeModeIgnoresAnnotations() {
        CompiledSpec cs = TestSupport.example("calc");
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "7;", false);
        assertEquals("program(stmts(stmt(expr(7), ;)))", r.tree().sexpr());
    }

    @Test
    void jsonOutputIsWellFormed() {
        CompiledSpec cs = TestSupport.example("json");
        Parser.Result r = TestSupport.parse(cs, Mode.LALR, "[\"x\\\"y\"]", true);
        String json = r.tree().toJson();
        assertTrue(json.startsWith("{\"label\": \"Array\""), json);
        assertTrue(json.contains("\"text\": \"\\\"x\\\\\\\"y\\\"\""), json);
    }
}
