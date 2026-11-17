package lalrgen.regex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M2: regex syntax, desugaring and error offsets. */
class RegexParserTest {

    @Test
    void precedenceStarBindsTighterThanConcatThanUnion() {
        RegexNode n = RegexParser.parse("ab*|c");
        RegexNode.Union u = (RegexNode.Union) n;
        RegexNode.Concat c = (RegexNode.Concat) u.left();
        assertTrue(c.right() instanceof RegexNode.Star);
    }

    @Test
    void sugarIsRemoved() {
        assertEquals("aa*", RegexNode.show(RegexParser.parse("a+")));
        assertEquals("a|()", RegexNode.show(RegexParser.parse("a?")));
        assertEquals("aa(a|())", RegexNode.show(RegexParser.parse("a{2,3}")));
        assertEquals("aaa*", RegexNode.show(RegexParser.parse("a{2,}")));
    }

    @Test
    void classesAndEscapes() {
        IntervalSet s = ((RegexNode.CharSet) RegexParser.parse("[a-c_0-9]")).set();
        assertTrue(s.contains('b') && s.contains('_') && s.contains('5'));
        assertFalse(s.contains('d'));
        IntervalSet neg = ((RegexNode.CharSet) RegexParser.parse("[^\\n]")).set();
        assertFalse(neg.contains('\n'));
        assertTrue(neg.contains('x') && neg.contains(0x1F600));
        IntervalSet dash = ((RegexNode.CharSet) RegexParser.parse("[-+]")).set();
        assertTrue(dash.contains('-') && dash.contains('+'));
        IntervalSet bracket = ((RegexNode.CharSet) RegexParser.parse("[]a]")).set();
        assertTrue(bracket.contains(']'));
        IntervalSet hex = ((RegexNode.CharSet) RegexParser.parse("[\\x00-\\x1F]")).set();
        assertEquals(32, hex.size());
        IntervalSet uni = ((RegexNode.CharSet) RegexParser.parse("\\u{1F600}")).set();
        assertTrue(uni.contains(0x1F600));
        IntervalSet word = ((RegexNode.CharSet) RegexParser.parse("[\\w-]")).set();
        assertTrue(word.contains('-') && word.contains('Z'));
    }

    @Test
    void dotExcludesOnlyNewline() {
        IntervalSet s = ((RegexNode.CharSet) RegexParser.parse(".")).set();
        assertFalse(s.contains('\n'));
        assertTrue(s.contains('\r'));
    }

    @Test
    void nullable() {
        assertTrue(RegexNode.nullable(RegexParser.parse("a*")));
        assertTrue(RegexNode.nullable(RegexParser.parse("a?b?")));
        assertTrue(RegexNode.nullable(RegexParser.parse("a|")));
        assertFalse(RegexNode.nullable(RegexParser.parse("a*b")));
    }

    private static int errorAt(String re) {
        return assertThrows(RegexSyntaxException.class, () -> RegexParser.parse(re)).offset();
    }

    @Test
    void errorsCarryOffsets() {
        assertEquals(1, errorAt("a(b"));
        assertEquals(1, errorAt("a)b"));
        assertEquals(0, errorAt("*a"));
        assertEquals(1, errorAt("a[bc"));
        assertEquals(1, errorAt("a{3,2}"));
        assertEquals(0, errorAt("\\q"));
        assertEquals(2, errorAt("a[z-a]")); // points at the bad range
        assertEquals(1, errorAt("a{5000}"));
    }

    @Test
    void intervalSetOperations() {
        IntervalSet a = IntervalSet.range('a', 'f').union(IntervalSet.range('d', 'k')).union(IntervalSet.of('m'));
        assertEquals(2, a.rangeCount());
        assertEquals(12, a.size());
        assertEquals("[a-km]", a.toString());
        IntervalSet c = a.complement();
        assertFalse(c.contains('e'));
        assertTrue(c.contains('l'));
        assertEquals(a, c.complement());
        assertEquals("[^\\n]", IntervalSet.of('\n').complement().toString());
    }
}
