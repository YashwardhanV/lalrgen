package lalrgen.lexer;

import lalrgen.TestSupport;
import lalrgen.core.CompiledSpec;
import lalrgen.spec.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3: the lexer runtime (maximal munch, priority, positions, errors). */
class LexerTest {

    private static List<String> kinds(CompiledSpec cs, String input) {
        Lexer.Result r = Lexer.tokenize(cs.lexer(), "in", input);
        assertTrue(r.ok(), r.errors().toString());
        return r.tokens().stream().map(Token::kind).toList();
    }

    @Test
    void keywordLiteralBeatsIdentifierButLongestMatchWins() {
        CompiledSpec cs = TestSupport.compile("%token ID /[a-z]+/\n%skip / +/\n%%\ns : 'if' ID | ID ;\n");
        assertEquals(List.of("'if'", "ID", "$"), kinds(cs, "if iffy"));
        assertEquals(List.of("ID", "$"), kinds(cs, "i"));
    }

    @Test
    void maximalMunch() {
        CompiledSpec cs = TestSupport.compile("%%\ns : '>' | '>=' | '>' '>=' | '>=' '>' ;\n");
        assertEquals(List.of("'>='", "'>'", "$"), kinds(cs, ">=>"));
        assertEquals(List.of("'>'", "'>='", "$"), kinds(cs, ">>="));
    }

    @Test
    void maximalMunchBacksUpToLastAcceptingPosition() {
        // "1." is not a NUM by itself; the lexer must back up from "1." to "1" and lex "." alone
        CompiledSpec cs = TestSupport.compile("%token NUM /[0-9]+(\\.[0-9]+)?/\n%%\ns : NUM '.' | NUM ;\n");
        assertEquals(List.of("NUM", "'.'", "$"), kinds(cs, "1."));
        assertEquals(List.of("NUM", "$"), kinds(cs, "1.5"));
    }

    @Test
    void linesAndColumns() {
        CompiledSpec cs = TestSupport.compile("%token W /[a-z]+/\n%skip /[ \\n\\t]+/\n%%\ns : W W W ;\n");
        List<Token> t = Lexer.tokenize(cs.lexer(), "in", "ab\n  cd\té").tokens();
        assertEquals(new Pos(1, 1), new Pos(t.get(0).line(), t.get(0).col()));
        assertEquals(new Pos(2, 3), new Pos(t.get(1).line(), t.get(1).col()));
        assertEquals("cd", t.get(1).text());
        assertEquals(3, t.size(), "é is not a W: it is an error, not a token");
        assertTrue(t.get(2).isEof());
        assertEquals(new Pos(2, 7), new Pos(t.get(2).line(), t.get(2).col()));
    }

    @Test
    void unexpectedCharactersAreGroupedAndLexingContinues() {
        CompiledSpec cs = TestSupport.compile("%token W /[a-z]+/\n%skip / +/\n%%\ns : W W ;\n");
        Lexer.Result r = Lexer.tokenize(cs.lexer(), "in", "ab ##! cd");
        assertFalse(r.ok());
        assertEquals(1, r.errors().size());
        assertEquals(new Pos(1, 4), r.errors().get(0).pos());
        assertTrue(r.errors().get(0).message().contains("\"##!\""));
        assertEquals(3, r.tokens().size()); // ab, cd, $
    }

    @Test
    void errorLimit() {
        CompiledSpec cs = TestSupport.compile("%token W /[a-z]+/\n%%\ns : W ;\n");
        Lexer.Result r = Lexer.tokenize(cs.lexer(), "in", "a1a2a3a4a5", 3);
        assertEquals(4, r.errors().size());
        assertTrue(r.errors().get(3).message().contains("too many"));
    }

    @Test
    void shadowedTokenWarning() {
        CompiledSpec cs = TestSupport.compile("%token ID /[a-z]+/\n%token KW /if/\n%%\ns : ID | KW ;\n");
        assertTrue(cs.warnings().stream().anyMatch(w -> w.message().contains("KW can never be matched")),
                cs.warnings().toString());
    }

    @Test
    void skipRulesProduceNoTokens() {
        CompiledSpec cs = TestSupport.compile("%token W /[a-z]+/\n%skip /#[^\\n]*/\n%skip /[ \\n]+/\n%%\ns : W W ;\n");
        assertEquals(List.of("W", "W", "$"), kinds(cs, "ab # comment\ncd"));
    }
}
