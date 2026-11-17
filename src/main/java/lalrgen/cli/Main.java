package lalrgen.cli;

import lalrgen.core.CompiledSpec;
import lalrgen.core.SpecCompiler;
import lalrgen.lexer.Lexer;
import lalrgen.lexer.Token;
import lalrgen.lr.LRAutomaton;
import lalrgen.lr.Mode;
import lalrgen.lr.ParseTable;
import lalrgen.runtime.Parser;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.SpecException;
import lalrgen.viz.BisonExporter;
import lalrgen.viz.DotExporter;
import lalrgen.viz.JsonExporter;
import lalrgen.viz.TableFormatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point. {@link #run} does all the work and returns the exit code, so tests
 * can call it without exiting the JVM.
 *
 * <p>Exit codes: 0 = success; 1 = error (bad spec, bad input, I/O, or conflicts with --strict);
 * 2 = success with unresolved grammar conflicts (check, tables, compare).
 */
public final class Main {
    private Main() {}

    static final String USAGE = """
            LALRGen: a Lex/Yacc clone (regex -> NFA -> DFA lexer, SLR / LR(1) / LALR(1) parser)

            Usage: lalrgen <command> <spec.lg> [input] [options]

            Commands:
              check   spec.lg                 validate the spec and report conflicts
              grammar spec.lg                 list the numbered productions
              sets    spec.lg                 nullable, FIRST and FOLLOW sets
              states  spec.lg                 list the item sets of the LR automaton
              tables  spec.lg                 print the ACTION / GOTO table
              lex     spec.lg input.txt       print the token stream
              parse   spec.lg input.txt       parse and print the tree
              dot     spec.lg --what W        Graphviz DOT for W = nfa|dfa|mindfa|lr0|lr1|lalr
              compare spec.lg                 SLR vs LR(1) vs LALR state and conflict counts
              bison   spec.lg                 export the grammar as a Bison .y file

            Options:
              --mode slr|lr1|lalr   table construction (default lalr)
              --format text|json    output format for tables (default text)
              --what W              what to draw (dot command)
              -o, --output FILE     write output to FILE instead of stdout
              --trace               parse: print every shift/reduce step
              --ast                 parse: build the AST from -> annotations
              --tree                parse: build the full parse tree (default)
              --json                parse: print the tree as JSON
              --no-recover          parse: stop at the first syntax error
              --verbose             also list conflicts resolved by precedence
              --expand              show conflict examples as token strings
              --strict              treat unresolved conflicts as errors
              -h, --help            show this help
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Parsed command-line options. */
    static final class Opts {
        String command;
        String spec;
        String input;
        Mode mode = Mode.LALR;
        String format = "text";
        String what;
        String output;
        boolean trace;
        boolean ast;
        boolean json;
        boolean recover = true;
        boolean verbose;
        boolean expand;
        boolean strict;
        boolean help;
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Opts o;
        try {
            o = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("lalrgen: " + e.getMessage());
            err.println("Run 'lalrgen --help' for usage.");
            return 1;
        }
        if (o.help || o.command == null) {
            out.print(USAGE);
            return o.help ? 0 : 1;
        }
        if (o.spec == null) {
            err.println("lalrgen: '" + o.command + "' needs a spec file");
            return 1;
        }
        CompiledSpec cs;
        try {
            String text = read(o.spec);
            cs = SpecCompiler.compile(Path.of(o.spec).getFileName().toString(), text);
        } catch (SpecException e) {
            for (Diagnostic d : e.diagnostics()) err.println(d);
            err.println(e.diagnostics().size() + " error(s) in " + o.spec);
            return 1;
        } catch (IOException e) {
            err.println("lalrgen: cannot read " + describe(e));
            return 1;
        }
        for (Diagnostic d : cs.warnings()) err.println(d);
        try {
            return dispatch(o, cs, out, err);
        } catch (IOException e) {
            err.println("lalrgen: cannot read " + describe(e));
            return 1;
        } catch (IllegalArgumentException e) {
            err.println("lalrgen: " + e.getMessage());
            return 1;
        }
    }

    private static int dispatch(Opts o, CompiledSpec cs, PrintStream out, PrintStream err) throws IOException {
        switch (o.command) {
            case "check" -> {
                ParseTable t = cs.table(o.mode);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%s: %d terminals, %d non-terminals, %d productions, %d lexer rules%n",
                        cs.spec().fileName(), cs.grammar().terminalCount(), cs.grammar().nonTerminalCount() - 1,
                        cs.grammar().productions().size() - 1, cs.lexer().rules().size()));
                sb.append(String.format("Lexer: NFA %d states, DFA %d states, minimized DFA %d states, %d character classes%n",
                        cs.lexer().nfa().stateCount(), cs.lexer().dfa().stateCount(), cs.lexer().minDfa().stateCount(),
                        cs.lexer().minDfa().classes().count()));
                sb.append(TableFormatter.summary(t)).append('\n');
                String report = TableFormatter.conflicts(t, cs.analysis(), o.verbose, o.expand);
                if (!report.isEmpty()) sb.append('\n').append(report);
                emit(o, out, sb.toString());
                return conflictExit(o, t, err);
            }
            case "grammar" -> {
                emit(o, out, TableFormatter.productions(cs.grammar()));
                return 0;
            }
            case "sets" -> {
                emit(o, out, cs.analysis().describe());
                return 0;
            }
            case "states" -> {
                LRAutomaton m = cs.automaton(o.mode);
                emit(o, out, m.describe());
                return 0;
            }
            case "tables" -> {
                ParseTable t = cs.table(o.mode);
                String text = switch (o.format) {
                    case "json" -> JsonExporter.export(cs, t);
                    case "text" -> TableFormatter.productions(cs.grammar()) + "\n" + TableFormatter.summary(t) + "\n\n"
                            + TableFormatter.table(t);
                    default -> throw new IllegalArgumentException("unknown format " + o.format);
                };
                emit(o, out, text);
                if (t.hasUnresolvedConflicts() && o.format.equals("text")) {
                    err.print(TableFormatter.conflicts(t, cs.analysis(), o.verbose, o.expand));
                }
                return conflictExit(o, t, err);
            }
            case "lex" -> {
                if (o.input == null) throw new IllegalArgumentException("lex needs an input file");
                Lexer.Result r = Lexer.tokenize(cs.lexer(), Path.of(o.input).getFileName().toString(), read(o.input));
                StringBuilder sb = new StringBuilder();
                for (Token t : r.tokens()) sb.append(t).append('\n');
                emit(o, out, sb.toString());
                for (Diagnostic d : r.errors()) err.println(d);
                return r.ok() ? 0 : 1;
            }
            case "parse" -> {
                return parse(o, cs, out, err);
            }
            case "dot" -> {
                if (o.what == null) throw new IllegalArgumentException("dot needs --what nfa|dfa|mindfa|lr0|lr1|lalr");
                String text = switch (o.what) {
                    case "nfa" -> DotExporter.nfa(cs.lexer().nfa(), cs.lexer().rules());
                    case "dfa" -> DotExporter.dfa(cs.lexer().dfa(), cs.lexer().rules(), "DFA");
                    case "mindfa" -> DotExporter.dfa(cs.lexer().minDfa(), cs.lexer().rules(), "MinDFA");
                    case "lr0" -> DotExporter.lr(cs.lr0(), cs.table(Mode.SLR));
                    case "lr1" -> DotExporter.lr(cs.lr1(), cs.table(Mode.LR1));
                    case "lalr" -> DotExporter.lr(cs.automaton(Mode.LALR), cs.table(Mode.LALR));
                    default -> throw new IllegalArgumentException("unknown --what '" + o.what + "'");
                };
                emit(o, out, text);
                return 0;
            }
            case "compare" -> {
                StringBuilder sb = new StringBuilder(String.format("%-6s %7s %5s %5s%n", "mode", "states", "s/r", "r/r"));
                for (Mode m : Mode.values()) {
                    ParseTable t = cs.table(m);
                    sb.append(String.format("%-6s %7d %5d %5d%n", m.label(), t.stateCount(), t.shiftReduceCount(), t.reduceReduceCount()));
                }
                emit(o, out, sb.toString());
                return conflictExit(o, cs.table(o.mode), err);
            }
            case "bison" -> {
                emit(o, out, BisonExporter.export(cs));
                return 0;
            }
            default -> throw new IllegalArgumentException("unknown command '" + o.command + "'");
        }
    }

    private static int parse(Opts o, CompiledSpec cs, PrintStream out, PrintStream err) throws IOException {
        if (o.input == null) throw new IllegalArgumentException("parse needs an input file");
        ParseTable t = cs.table(o.mode);
        if (t.hasUnresolvedConflicts()) {
            err.println("warning: " + TableFormatter.summary(t) + " (run 'check' for details)");
            if (o.strict) return 1;
        }
        String inputName = Path.of(o.input).getFileName().toString();
        Lexer.Result lex = Lexer.tokenize(cs.lexer(), inputName, read(o.input));
        if (!lex.ok() && !o.recover) {
            for (Diagnostic d : lex.errors()) err.println(d);
            return 1;
        }
        Parser.Options popts = new Parser.Options(o.ast, o.trace, o.recover, Parser.DEFAULT_MAX_ERRORS);
        Parser.Result r = Parser.parse(t, lex.tokens(), popts, inputName);
        StringBuilder sb = new StringBuilder();
        if (o.trace) sb.append(formatTrace(r.trace())).append('\n');
        if (r.tree() != null && lex.ok()) {
            sb.append(o.json ? r.tree().toJson() + "\n" : r.tree().pretty());
        }
        emit(o, out, sb.toString());
        // lexical and syntax errors together, in source order
        List<Diagnostic> all = new java.util.ArrayList<>(lex.errors());
        all.addAll(r.errors());
        all.sort(java.util.Comparator.comparingInt((Diagnostic d) -> d.pos().line()).thenComparingInt(d -> d.pos().col()));
        for (Diagnostic d : all) err.println(d);
        if (r.ok() && lex.ok()) return 0;
        int total = r.errors().size() + lex.errors().size();
        err.println(total + " error(s)");
        return 1;
    }

    private static String formatTrace(List<Parser.Step> steps) {
        int ws = "stack".length();
        int wi = "input".length();
        for (Parser.Step s : steps) {
            ws = Math.max(ws, s.stack().length());
            wi = Math.max(wi, s.input().length());
        }
        ws = Math.min(ws, 70);
        wi = Math.min(wi, 40);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-4s  %-" + ws + "s  %-" + wi + "s  %s%n", "step", "stack", "input", "action"));
        int n = 1;
        for (Parser.Step s : steps) {
            sb.append(String.format("%-4d  %-" + ws + "s  %-" + wi + "s  %s%n", n++, clip(s.stack(), 70, true), s.input(), s.action()));
        }
        return sb.toString();
    }

    private static String clip(String s, int w, boolean keepEnd) {
        if (s.length() <= w) return s;
        return keepEnd ? "..." + s.substring(s.length() - w + 3) : s.substring(0, w - 3) + "...";
    }

    private static int conflictExit(Opts o, ParseTable t, PrintStream err) {
        if (!t.hasUnresolvedConflicts()) return 0;
        if (o.strict) {
            err.println("error: unresolved conflicts (--strict)");
            return 1;
        }
        return 2;
    }

    private static void emit(Opts o, PrintStream out, String text) throws IOException {
        if (o.output != null) {
            Files.writeString(Path.of(o.output), text, StandardCharsets.UTF_8);
        } else {
            out.print(text);
            out.flush();
        }
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String describe(IOException e) {
        return e instanceof NoSuchFileException ? e.getMessage() + " (no such file)" : e.getMessage();
    }

    static Opts parseArgs(String[] args) {
        Opts o = new Opts();
        int positional = 0;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> o.help = true;
                case "--mode" -> o.mode = Mode.parse(value(args, ++i, a));
                case "--format" -> o.format = value(args, ++i, a);
                case "--what" -> o.what = value(args, ++i, a);
                case "-o", "--output" -> o.output = value(args, ++i, a);
                case "--trace" -> o.trace = true;
                case "--ast" -> o.ast = true;
                case "--tree" -> o.ast = false;
                case "--json" -> o.json = true;
                case "--no-recover" -> o.recover = false;
                case "--verbose", "-v" -> o.verbose = true;
                case "--expand" -> o.expand = true;
                case "--strict" -> o.strict = true;
                default -> {
                    if (a.startsWith("-") && a.length() > 1) throw new IllegalArgumentException("unknown option " + a);
                    switch (positional++) {
                        case 0 -> o.command = a;
                        case 1 -> o.spec = a;
                        case 2 -> o.input = a;
                        default -> throw new IllegalArgumentException("unexpected argument " + a);
                    }
                }
            }
        }
        if (o.command != null && !List.of("check", "grammar", "sets", "states", "tables", "lex", "parse", "dot",
                "compare", "bison").contains(o.command)) {
            throw new IllegalArgumentException("unknown command '" + o.command + "'");
        }
        if (!o.format.equals("text") && !o.format.equals("json")) {
            throw new IllegalArgumentException("--format must be text or json");
        }
        return o;
    }

    private static String value(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " needs a value");
        return args[i];
    }
}
