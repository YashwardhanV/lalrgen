package lalrgen;

import lalrgen.core.CompiledSpec;
import lalrgen.lr.Mode;
import lalrgen.lr.ParseTable;
import lalrgen.runtime.Parser;
import lalrgen.lexer.Lexer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every example grammar builds with the expected conflicts, and every sample input in
 * examples/inputs parses (files named *_bad*.txt must fail with errors).
 */
class ExamplesTest {

    /** Expected unresolved conflicts per example: {SLR s/r, SLR r/r, LR1 s/r, LR1 r/r, LALR s/r, LALR r/r}. */
    static final Map<String, int[]> EXPECTED = Map.of(
            "expr", new int[] {0, 0, 0, 0, 0, 0},
            "cc", new int[] {0, 0, 0, 0, 0, 0},
            "not_slr", new int[] {1, 0, 0, 0, 0, 0},
            "not_lalr", new int[] {0, 2, 0, 0, 0, 2},
            "calc", new int[] {0, 0, 0, 0, 0, 0},
            "dangling_else", new int[] {1, 0, 1, 0, 1, 0},
            "dangling_else_prec", new int[] {0, 0, 0, 0, 0, 0},
            "nonassoc", new int[] {0, 0, 0, 0, 0, 0},
            "json", new int[] {0, 0, 0, 0, 0, 0},
            "minic", new int[] {0, 0, 0, 0, 0, 0});

    @Test
    void everyExampleIsListed() throws IOException {
        try (Stream<Path> s = Files.list(TestSupport.examples())) {
            List<String> names = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".lg"))
                    .map(n -> n.substring(0, n.length() - 3)).sorted().toList();
            assertEquals(EXPECTED.keySet().stream().sorted().toList(), names);
        }
    }

    @TestFactory
    List<DynamicTest> conflictCounts() {
        List<DynamicTest> tests = new ArrayList<>();
        EXPECTED.forEach((name, exp) -> tests.add(DynamicTest.dynamicTest(name, () -> {
            CompiledSpec cs = TestSupport.example(name);
            int k = 0;
            for (Mode m : Mode.values()) {
                ParseTable t = cs.table(m);
                assertEquals(exp[k++], t.shiftReduceCount(), name + " " + m + " s/r");
                assertEquals(exp[k++], t.reduceReduceCount(), name + " " + m + " r/r");
            }
        })));
        return tests;
    }

    @TestFactory
    List<DynamicTest> sampleInputs() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> s = Files.list(TestSupport.examples().resolve("inputs"))) {
            for (Path p : s.sorted().toList()) {
                String file = p.getFileName().toString();
                String grammar = file.replaceAll("(_ok|_bad)?\\.txt$", "");
                boolean bad = file.contains("_bad");
                tests.add(DynamicTest.dynamicTest(file, () -> {
                    CompiledSpec cs = TestSupport.example(grammar);
                    Lexer.Result lex = Lexer.tokenize(cs.lexer(), file, TestSupport.read(p));
                    Parser.Result r = Parser.parse(cs.table(Mode.LALR), lex.tokens(),
                            Parser.Options.defaults().withAst(true).withRecover(true), file);
                    if (bad) {
                        assertFalse(lex.ok() && r.ok(), file + " should have errors");
                    } else {
                        assertTrue(lex.ok(), lex.errors().toString());
                        assertTrue(r.ok(), r.errors().toString());
                    }
                }));
            }
        }
        return tests;
    }
}
