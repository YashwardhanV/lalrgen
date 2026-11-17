package lalrgen.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command-line interface: outputs and exit codes. */
class MainTest {
    record Run(int code, String out, String err) {}

    static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void help() {
        Run r = run("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("Usage: lalrgen"));
        assertEquals(1, run().code());
    }

    @Test
    void checkExitCodes() {
        assertEquals(0, run("check", "examples/calc.lg").code());
        Run conflict = run("check", "examples/dangling_else.lg");
        assertEquals(2, conflict.code());
        assertTrue(conflict.out().contains("1 shift/reduce"), conflict.out());
        assertEquals(1, run("check", "examples/dangling_else.lg", "--strict").code());
        assertEquals(0, run("check", "examples/not_slr.lg").code());
        assertEquals(2, run("check", "examples/not_slr.lg", "--mode", "slr").code());
    }

    @Test
    void verboseListsResolvedConflicts() {
        Run r = run("check", "examples/calc.lg", "--verbose");
        assertTrue(r.out().contains("Resolved conflict"), r.out());
        assertTrue(r.out().contains("%left"), r.out());
    }

    @Test
    void compare() {
        Run r = run("compare", "examples/cc.lg");
        assertEquals(0, r.code());
        assertTrue(r.out().matches("(?s).*slr\\s+7\\s+0\\s+0.*lr1\\s+10\\s+0\\s+0.*lalr\\s+7\\s+0\\s+0.*"), r.out());
    }

    @Test
    void specErrorsGoToStderr() {
        Run r = run("check", "src/test/resources/broken.lg");
        assertEquals(1, r.code());
        assertTrue(r.err().contains("broken.lg:5:5: error: undefined symbol 'nope'"), r.err());
        assertTrue(r.err().contains("error(s) in"), r.err());
    }

    @Test
    void missingFile() {
        Run r = run("check", "no/such/file.lg");
        assertEquals(1, r.code());
        assertTrue(r.err().contains("cannot read"), r.err());
    }

    @Test
    void lexAndParse() {
        Run lex = run("lex", "examples/calc.lg", "examples/inputs/calc_ok.txt");
        assertEquals(0, lex.code());
        assertTrue(lex.out().contains("ID\t\"x\""), lex.out());
        Run parse = run("parse", "examples/calc.lg", "examples/inputs/calc_ok.txt", "--ast");
        assertEquals(0, parse.code(), parse.err());
        assertTrue(parse.out().startsWith("Program"), parse.out());
        Run bad = run("parse", "examples/calc.lg", "examples/inputs/calc_bad.txt");
        assertEquals(1, bad.code());
        assertTrue(bad.err().contains("calc_bad.txt:1:8: error"), bad.err());
        assertTrue(bad.err().contains("4 error(s)"), bad.err());
    }

    @Test
    void traceAndJson() {
        Run r = run("parse", "examples/expr.lg", "examples/inputs/expr.txt", "--trace", "--mode", "slr");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("reduce T -> T '*' F"), r.out());
        Run j = run("parse", "examples/json.lg", "examples/inputs/json_ok.txt", "--ast", "--json");
        assertTrue(j.out().startsWith("{\"label\": \"Object\""), j.out());
    }

    @Test
    void tablesTextAndJson(@TempDir Path tmp) throws Exception {
        Run text = run("tables", "examples/expr.lg", "--mode", "slr");
        assertTrue(text.out().contains("ACTION"), text.out());
        assertTrue(text.out().contains("acc"), text.out());
        Path out = tmp.resolve("t.json");
        assertEquals(0, run("tables", "examples/expr.lg", "--format", "json", "-o", out.toString()).code());
        String json = Files.readString(out);
        assertTrue(json.contains("\"action\": ["));
        assertTrue(json.contains("\"transitions\": ["));
    }

    @Test
    void dotOutputs() {
        for (String w : new String[] {"nfa", "dfa", "mindfa", "lr0", "lr1", "lalr"}) {
            Run r = run("dot", "examples/expr.lg", "--what", w);
            assertEquals(0, r.code(), w);
            assertTrue(r.out().startsWith("digraph"), w);
        }
        Run red = run("dot", "examples/dangling_else.lg", "--what", "lalr");
        assertTrue(red.out().contains("color=red"));
        assertEquals(1, run("dot", "examples/expr.lg").code());
    }

    @Test
    void otherCommands() {
        assertTrue(run("grammar", "examples/expr.lg").out().contains("1  E -> E '+' T"));
        assertTrue(run("sets", "examples/expr.lg").out().contains("FOLLOW = { $, '+', ')' }"));
        assertTrue(run("states", "examples/cc.lg", "--mode", "lr1").out().contains("State 9"));
        assertTrue(run("bison", "examples/calc.lg").out().contains("%left LIT_"));
    }

    @Test
    void badArguments() {
        assertEquals(1, run("frobnicate", "examples/expr.lg").code());
        assertEquals(1, run("check", "examples/expr.lg", "--mode", "ll1").code());
        assertEquals(1, run("check", "examples/expr.lg", "--bogus").code());
        assertEquals(1, run("parse", "examples/expr.lg").code());
    }
}
