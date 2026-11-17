package lalrgen;

import lalrgen.core.CompiledSpec;
import lalrgen.core.SpecCompiler;
import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.grammar.Production;
import lalrgen.lexer.Lexer;
import lalrgen.lexer.Token;
import lalrgen.lr.Mode;
import lalrgen.lr.ParseTable;
import lalrgen.runtime.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Shared helpers for the tests. */
public final class TestSupport {
    private TestSupport() {}

    public static CompiledSpec compile(String spec) {
        return SpecCompiler.compile("test.lg", spec);
    }

    public static Path examples() {
        return Path.of("examples").toAbsolutePath();
    }

    public static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompiledSpec example(String name) {
        return SpecCompiler.compile(name + ".lg", read(examples().resolve(name + ".lg")));
    }

    /** Lexes and parses input; fails the test on lexical errors. */
    public static Parser.Result parse(CompiledSpec cs, Mode mode, String input, boolean ast) {
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), "input", input);
        assertTrue(lex.ok(), () -> "lexical errors: " + lex.errors());
        return Parser.parse(cs.table(mode), lex.tokens(), Parser.Options.defaults().withAst(ast), "input");
    }

    /** Parses input in LALR mode with AST building and returns the AST in compact form. */
    public static String ast(CompiledSpec cs, String input) {
        Parser.Result r = parse(cs, Mode.LALR, input, true);
        if (!r.ok()) fail("parse failed: " + r.errors());
        return r.tree().sexpr();
    }

    public static boolean accepts(CompiledSpec cs, Mode mode, String input) {
        return parse(cs, mode, input, false).ok();
    }

    /** Builds a token list for a sequence of terminal ids (without running the lexer). */
    public static List<Token> tokens(CompiledSpec cs, List<Integer> terminals) {
        Grammar g = cs.grammar();
        List<Token> out = new ArrayList<>();
        int col = 1;
        for (int t : terminals) {
            int rule = -1;
            for (int r = 0; r < cs.lexer().rules().size(); r++) {
                if (g.terminalOfRule(r) == t) {
                    rule = r;
                    break;
                }
            }
            if (rule < 0) throw new IllegalArgumentException("no lexer rule for " + g.name(t));
            out.add(new Token(rule, g.name(t), g.name(t), 1, col++));
        }
        out.add(Token.eof(1, col));
        return out;
    }

    public static boolean accepts(ParseTable table, CompiledSpec cs, List<Integer> terminals) {
        return Parser.parse(table, tokens(cs, terminals)).ok();
    }

    /**
     * A random sentence of the grammar: expand non-terminals with random productions; past
     * maxDepth, use the shortest derivation so the expansion terminates.
     */
    public static List<Integer> randomSentence(CompiledSpec cs, Random rnd, int maxDepth) {
        List<Integer> out = new ArrayList<>();
        expand(cs.grammar(), cs.analysis(), cs.grammar().start(), rnd, 0, maxDepth, out);
        return out;
    }

    private static void expand(Grammar g, GrammarAnalysis a, int sym, Random rnd, int depth, int maxDepth, List<Integer> out) {
        if (g.isTerminal(sym)) {
            out.add(sym);
            return;
        }
        if (depth >= maxDepth) {
            out.addAll(a.shortestExpansion(sym));
            return;
        }
        List<Production> ps = g.productionsOf(sym);
        Production p = ps.get(rnd.nextInt(ps.size()));
        for (int s : p.rhs()) expand(g, a, s, rnd, depth + 1, maxDepth, out);
    }

    /** A random edit of a token list: delete, duplicate, swap or replace one token. */
    public static List<Integer> mutate(List<Integer> s, int terminalCount, Random rnd) {
        List<Integer> m = new ArrayList<>(s);
        int kind = rnd.nextInt(4);
        if (m.isEmpty() || kind == 3) {
            m.add(m.isEmpty() ? 0 : rnd.nextInt(m.size() + 1), 1 + rnd.nextInt(terminalCount - 1));
            return m;
        }
        int i = rnd.nextInt(m.size());
        switch (kind) {
            case 0 -> m.remove(i);
            case 1 -> m.add(i, m.get(i));
            default -> {
                int j = rnd.nextInt(m.size());
                int tmp = m.get(i);
                m.set(i, m.get(j));
                m.set(j, tmp);
            }
        }
        return m;
    }
}
