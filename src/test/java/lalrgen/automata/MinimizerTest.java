package lalrgen.automata;

import lalrgen.regex.RegexParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M3: subset construction and minimization on textbook examples. */
class MinimizerTest {

    @Test
    void dragonBookExampleMinimizesToFourStates() {
        DFA dfa = SubsetConstruction.build(Thompson.build(RegexParser.parse("(a|b)*abb")));
        assertEquals(5, dfa.stateCount(), "subset construction gives A..E as in the dragon book");
        DFA min = Minimizer.hopcroft(dfa);
        assertEquals(4, min.stateCount());
        assertEquals(1, min.acceptingCount());
        assertEquals(4, Minimizer.moore(dfa).stateCount());
    }

    @Test
    void classesCompressTheAlphabet() {
        CharClasses cc = CharClasses.build(List.of(
                RegexParserHelper.set("[a-z]"), RegexParserHelper.set("[0-9a-f]"), RegexParserHelper.set("[^\\n]")));
        // [a-f], [g-z], [0-9], and "everything else except newline"
        assertEquals(4, cc.count());
        assertEquals(cc.classify('a'), cc.classify('f'));
        assertNotEquals(cc.classify('f'), cc.classify('g'));
        assertEquals(-1, cc.classify('\n'));
        assertEquals(cc.classify('!'), cc.classify(0x1F600));
    }

    @Test
    void tokenTypesAreNeverMerged() {
        // "xa" -> A and "ya" -> B reach accepting states with identical futures (none).
        // A textbook accepting / non-accepting initial partition would merge them.
        NFA nfa = Thompson.buildCombined(List.of(RegexParser.parse("xa"), RegexParser.parse("ya")));
        DFA dfa = SubsetConstruction.build(nfa);
        DFA min = Minimizer.hopcroft(dfa);
        assertEquals(0, min.run("xa"));
        assertEquals(1, min.run("ya"));
        assertEquals(2, min.acceptingCount());
        assertEquals(min.stateCount(), Minimizer.moore(dfa).stateCount());
    }

    @Test
    void sameTokenAcceptStatesDoMerge() {
        DFA dfa = SubsetConstruction.build(Thompson.build(RegexParser.parse("xa|ya")));
        DFA min = Minimizer.hopcroft(dfa);
        assertEquals(1, min.acceptingCount());
        assertEquals(3, min.stateCount());
    }

    @Test
    void priorityPicksLowestTag() {
        NFA nfa = Thompson.buildCombined(List.of(RegexParser.parse("if"), RegexParser.parse("[a-z]+")));
        DFA min = Minimizer.hopcroft(SubsetConstruction.build(nfa));
        assertEquals(0, min.run("if"));
        assertEquals(1, min.run("iff"));
        assertEquals(1, min.run("i"));
        assertTrue(min.run("") < 0);
    }

    /** Small helper to get the IntervalSet of a one-class regex. */
    static final class RegexParserHelper {
        static lalrgen.regex.IntervalSet set(String re) {
            return ((lalrgen.regex.RegexNode.CharSet) RegexParser.parse(re)).set();
        }
    }
}
