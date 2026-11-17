package lalrgen.spec;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.core.SpecCompiler;
import lalrgen.grammar.Grammar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: semantic checks of section 4.5 of spec.md, each with its source position. */
class SpecCompilerTest {

    private static Diagnostic error(String spec) {
        SpecException e = assertThrows(SpecException.class, () -> SpecCompiler.compile("t.lg", spec));
        return e.diagnostics().get(0);
    }

    @Test
    void undefinedSymbol() {
        Diagnostic d = error("%%\ns : 'a' x ;\n");
        assertEquals(new Pos(2, 9), d.pos());
        assertTrue(d.message().contains("undefined symbol 'x'"));
    }

    @Test
    void duplicateToken() {
        Diagnostic d = error("%token A /a/\n%token A /b/\n%%\ns : A ;\n");
        assertEquals(new Pos(2, 1), d.pos());
        assertTrue(d.message().contains("duplicate token 'A'"));
    }

    @Test
    void invalidRegexPointsIntoTheRegex() {
        Diagnostic d = error("%token A /a(b/\n%%\ns : A ;\n");
        assertEquals(new Pos(1, 12), d.pos()); // the '('
        assertTrue(d.message().contains("missing ')'"), d.message());
    }

    @Test
    void emptyMatchingRegex() {
        Diagnostic d = error("%token A /a*/\n%%\ns : A ;\n");
        assertEquals(new Pos(1, 11), d.pos());
        assertTrue(d.message().contains("matches the empty string"));
    }

    @Test
    void nonProductiveNonTerminal() {
        Diagnostic d = error("%%\ns : 'a' | t ;\nt : t 'b' ;\n");
        assertEquals(new Pos(3, 1), d.pos());
        assertTrue(d.message().contains("'t' cannot derive any string"));
    }

    @Test
    void unreachableIsAWarning() {
        CompiledSpec cs = SpecCompiler.compile("t.lg", "%%\ns : 'a' ;\nu : 'b' ;\n");
        assertEquals(1, cs.warnings().size());
        assertEquals(new Pos(3, 1), cs.warnings().get(0).pos());
        assertTrue(cs.warnings().get(0).message().contains("unreachable"));
    }

    @Test
    void unusedTokenIsAWarning() {
        CompiledSpec cs = SpecCompiler.compile("t.lg", "%token X /x/\n%%\ns : 'a' ;\n");
        assertTrue(cs.warnings().get(0).message().contains("never used"));
    }

    @Test
    void childReferenceOutOfRange() {
        Diagnostic d = error("%%\ns : 'a' 'b' -> P($3) ;\n");
        assertTrue(d.message().contains("$3 is out of range"));
    }

    @Test
    void precWithoutPrecedence() {
        Diagnostic d = error("%%\ns : 'a' %prec FOO ;\n");
        assertTrue(d.message().contains("no precedence declared"));
    }

    @Test
    void tokenUsedAsRuleName() {
        Diagnostic d = error("%token s /s/\n%%\ns : 'a' ;\n");
        assertTrue(d.message().contains("cannot also be a rule name"));
    }

    @Test
    void startMustHaveRules() {
        Diagnostic d = error("%start nope\n%%\ns : 'a' ;\n");
        assertTrue(d.message().contains("has no rules"));
    }

    @Test
    void precedenceNameUsedAsSymbol() {
        Diagnostic d = error("%left UMINUS\n%%\ns : UMINUS ;\n");
        assertTrue(d.message().contains("only a precedence name"));
    }

    @Test
    void symbolNumbering() {
        CompiledSpec cs = TestSupport.compile("%token NUM /[0-9]+/\n%skip / +/\n%%\ne : e '+' NUM | NUM ;\n");
        Grammar g = cs.grammar();
        assertEquals("$", g.name(0));
        assertEquals("'+'", g.name(1));
        assertEquals("NUM", g.name(2));
        assertEquals(3, g.terminalCount());
        assertEquals("e'", g.name(3));
        assertEquals("e", g.name(4));
        assertEquals("e' -> e", g.production(0).show(g));
        assertEquals("e -> e '+' NUM", g.production(1).show(g));
        // lexer rules: literal first, then NUM, then the skip rule
        assertEquals(1, g.terminalOfRule(0));
        assertEquals(2, g.terminalOfRule(1));
        assertEquals(-1, g.terminalOfRule(2));
    }

    @Test
    void productionPrecedenceFromRightmostTerminalOrPrec() {
        CompiledSpec cs = TestSupport.compile("""
                %token N /[0-9]+/
                %left '+'
                %left '*'
                %right NEG
                %%
                e : e '+' e | e '*' e | '-' e %prec NEG | N ;
                """);
        Grammar g = cs.grammar();
        assertEquals(1, g.production(1).precLevel());
        assertEquals(2, g.production(2).precLevel());
        assertEquals(3, g.production(3).precLevel());
        assertEquals(Assoc.RIGHT, g.production(3).assoc());
        assertEquals(0, g.production(4).precLevel());
    }
}
