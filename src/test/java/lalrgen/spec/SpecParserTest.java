package lalrgen.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: the spec-file parser. */
class SpecParserTest {

    static final String CALC = """
            # comment line
            %token NUM   /[0-9]+(\\.[0-9]+)?/
            %token ID    /[a-z]+/
            %skip        /[ \\t\\n]+/
            %left  '+' '-'
            %left  '*'
            %right UMINUS
            %start expr
            %%
            expr : expr '+' expr          -> Add($1, $3)
                 | '-' expr %prec UMINUS  -> Neg($2)
                 | '(' expr ')'           -> $2
                 | NUM                    -> Num($1)
                 | %empty
                 ;
            """;

    @Test
    void parsesAllSections() {
        SpecFile f = SpecParser.parse("calc.lg", CALC);
        assertEquals(3, f.tokens().size());
        assertEquals("NUM", f.tokens().get(0).name());
        assertEquals("[0-9]+(\\.[0-9]+)?", f.tokens().get(0).regex());
        assertEquals(TokenDef.Kind.SKIP, f.tokens().get(2).kind());
        assertEquals(3, f.precs().size());
        assertEquals(Assoc.LEFT, f.precs().get(0).assoc());
        assertEquals(List.of("'+'", "'-'"), f.precs().get(0).names().stream().map(SymRef::name).toList());
        assertEquals(1, f.precs().get(0).level());
        assertEquals(3, f.precs().get(2).level());
        assertEquals("expr", f.start().name());
        assertEquals(1, f.rules().size());
        RuleDef r = f.rules().get(0);
        assertEquals(5, r.alts().size());
        Alt first = r.alts().get(0);
        assertEquals(List.of("expr", "'+'", "expr"), first.symbols().stream().map(SymRef::name).toList());
        assertTrue(first.symbols().get(1).literal());
        assertEquals("+", first.symbols().get(1).text());
        assertEquals("Add($1, $3)", first.ast().toString());
        assertEquals("UMINUS", r.alts().get(1).prec().name());
        assertInstanceOf(AstExpr.ChildRef.class, r.alts().get(2).ast());
        assertTrue(r.alts().get(4).symbols().isEmpty());
        assertNull(r.alts().get(4).ast());
    }

    @Test
    void regexPositionPointsAtFirstCharacter() {
        SpecFile f = SpecParser.parse("t.lg", "%token AB /ab/\n%%\ns : AB ;\n");
        assertEquals(new Pos(1, 1), f.tokens().get(0).pos());
        assertEquals(new Pos(1, 12), f.tokens().get(0).regexPos());
    }

    @Test
    void escapedSlashInsideRegex() {
        SpecFile f = SpecParser.parse("t.lg", "%token C /\\/\\/[^\\n]*/\n%%\ns : C ;\n");
        assertEquals("\\/\\/[^\\n]*", f.tokens().get(0).regex());
    }

    @Test
    void literalEscapesAndUnicode() {
        SpecFile f = SpecParser.parse("t.lg", "%%\ns : '\\'' 'अगर' '\\\\' ;\n");
        List<SymRef> syms = f.rules().get(0).alts().get(0).symbols();
        assertEquals("'", syms.get(0).text());
        assertEquals("अगर", syms.get(1).text());
        assertEquals("\\", syms.get(2).text());
    }

    @Test
    void emptyAlternativesAndListCtors() {
        SpecFile f = SpecParser.parse("t.lg", "%%\nxs : %empty -> List | xs 'x' -> List($1, $2) | ;\n");
        assertEquals(3, f.rules().get(0).alts().size());
        assertEquals("List()", f.rules().get(0).alts().get(0).ast().toString());
    }

    private static Diagnostic onlyError(String text) {
        SpecException e = assertThrows(SpecException.class, () -> SpecParser.parse("t.lg", text));
        return e.diagnostics().get(0);
    }

    @Test
    void missingSeparator() {
        assertTrue(onlyError("%token A /a/\n").message().contains("missing '%%'"));
    }

    @Test
    void unknownDirective() {
        Diagnostic d = onlyError("%tokn A /a/\n%%\ns : A ;\n");
        assertEquals(new Pos(1, 1), d.pos());
        assertTrue(d.message().contains("unknown directive '%tokn'"));
    }

    @Test
    void unterminatedRegex() {
        Diagnostic d = onlyError("%token A /abc\n%%\ns : A ;\n");
        assertEquals(new Pos(1, 10), d.pos());
        assertTrue(d.message().contains("unterminated regex"));
    }

    @Test
    void missingSemicolonIsReportedAtNextRule() {
        Diagnostic d = onlyError("%%\na : 'x'\nb : 'y' ;\n");
        assertEquals(new Pos(3, 1), d.pos());
        assertTrue(d.message().contains("missing ';'"), d.message());
    }

    @Test
    void unterminatedLiteral() {
        Diagnostic d = onlyError("%%\na : 'x ;\n");
        assertEquals(new Pos(2, 5), d.pos());
        assertTrue(d.message().contains("unterminated literal"));
    }

    @Test
    void badAstAnnotation() {
        Diagnostic d = onlyError("%%\na : 'x' -> $0 ;\n");
        assertTrue(d.message().contains("start at $1"));
    }

    @Test
    void severalErrorsAreCollected() {
        SpecException e = assertThrows(SpecException.class,
                () -> SpecParser.parse("t.lg", "%bogus\n%token\n%%\na : 'x' ? ;\nb : 'y' ;\n"));
        assertEquals(3, e.diagnostics().size(), e.getMessage());
        assertEquals(1, e.diagnostics().get(0).pos().line());
        assertEquals(2, e.diagnostics().get(1).pos().line());
        assertEquals(4, e.diagnostics().get(2).pos().line());
    }
}
