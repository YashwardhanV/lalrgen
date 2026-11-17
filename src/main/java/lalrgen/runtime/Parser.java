package lalrgen.runtime;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.Production;
import lalrgen.lexer.Token;
import lalrgen.lr.Action;
import lalrgen.lr.ParseTable;
import lalrgen.spec.AstExpr;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.Pos;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The LR driver: a stack of states plus a parallel stack of tree nodes.
 *
 * <pre>
 * loop:
 *   s = top state, a = current token
 *   ACTION[s][a] = shift t  : push t, advance
 *                = reduce p : pop |rhs(p)| states, push GOTO[top][lhs(p)], build a node
 *                = accept   : done
 *                = error    : report, then recover (panic mode) or stop
 * </pre>
 *
 * <p><b>Panic-mode recovery:</b> skip input tokens until a synchronizing token (by default ';'
 * and '}', or %sync), then pop states until one has an action for that token, and resume. If no
 * state on the stack can handle it, that token is skipped too. To guarantee progress, a second
 * error at the same token without any shift in between discards the token.
 */
public final class Parser {
    public static final int DEFAULT_MAX_ERRORS = 20;

    /** Parser options. */
    public record Options(boolean buildAst, boolean trace, boolean recover, int maxErrors) {
        public static Options defaults() {
            return new Options(false, false, false, DEFAULT_MAX_ERRORS);
        }

        public Options withAst(boolean v) {
            return new Options(v, trace, recover, maxErrors);
        }

        public Options withTrace(boolean v) {
            return new Options(buildAst, v, recover, maxErrors);
        }

        public Options withRecover(boolean v) {
            return new Options(buildAst, trace, v, maxErrors);
        }
    }

    /** One step of a --trace listing. */
    public record Step(String stack, String input, String action) {}

    /**
     * Parse result. {@code tree} is null if the input was rejected or any error occurred.
     */
    public record Result(Node tree, boolean accepted, List<Diagnostic> errors, List<Step> trace) {
        public boolean ok() {
            return accepted && errors.isEmpty();
        }
    }

    private final ParseTable table;
    private final Grammar g;
    private final Options options;
    private final String fileName;

    private final List<Integer> states = new ArrayList<>();
    private final List<Node> values = new ArrayList<>();
    private final List<Integer> symbols = new ArrayList<>();
    private final List<Diagnostic> errors = new ArrayList<>();
    private final List<Step> trace = new ArrayList<>();

    private Parser(ParseTable table, Options options, String fileName) {
        this.table = table;
        this.g = table.grammar();
        this.options = options;
        this.fileName = fileName;
    }

    public static Result parse(ParseTable table, List<Token> tokens, Options options, String fileName) {
        return new Parser(table, options, fileName).run(tokens);
    }

    public static Result parse(ParseTable table, List<Token> tokens) {
        return parse(table, tokens, Options.defaults(), null);
    }

    private Result run(List<Token> tokens) {
        states.add(0);
        values.add(null);
        symbols.add(-1);
        int i = 0;
        int lastErrorAt = -1;
        boolean shiftedSinceError = true;
        while (true) {
            Token tok = tokens.get(i);
            int a = terminalOf(tok);
            int s = top();
            Action act = table.action(s, a);
            if (options.trace()) trace.add(new Step(stackString(), inputString(tokens, i), describe(act, tok)));
            switch (act) {
                case Action.Shift sh -> {
                    states.add(sh.state());
                    values.add(Node.leaf(tok));
                    symbols.add(a);
                    i++;
                    shiftedSinceError = true;
                }
                case Action.Reduce r -> reduce(g.production(r.production()));
                case Action.Accept acc -> {
                    Node tree = errors.isEmpty() ? values.get(values.size() - 1) : null;
                    return new Result(tree, true, List.copyOf(errors), List.copyOf(trace));
                }
                case null, default -> {
                    boolean cascade = i == lastErrorAt && !shiftedSinceError;
                    if (!cascade) report(tok, a, s, act); // a repeat at the same token is not a new error
                    if (!options.recover() || errors.size() >= options.maxErrors()) {
                        if (options.recover()) addError(tok, "too many syntax errors, stopping");
                        return new Result(null, false, List.copyOf(errors), List.copyOf(trace));
                    }
                    if (cascade) { // guarantee progress: discard the token we are stuck on
                        if (tok.isEof()) return new Result(null, false, List.copyOf(errors), List.copyOf(trace));
                        i++;
                    }
                    int resumeAt = recover(tokens, i);
                    if (resumeAt < 0) {
                        return new Result(null, false, List.copyOf(errors), List.copyOf(trace));
                    }
                    i = resumeAt;
                    lastErrorAt = i;
                    shiftedSinceError = false;
                }
            }
        }
    }

    private void reduce(Production p) {
        int n = p.length();
        List<Node> children = new ArrayList<>(values.subList(values.size() - n, values.size()));
        for (int k = 0; k < n; k++) {
            states.remove(states.size() - 1);
            values.remove(values.size() - 1);
            symbols.remove(symbols.size() - 1);
        }
        int target = table.gotoState(top(), p.lhs());
        if (target < 0) throw new IllegalStateException("missing GOTO entry: corrupt table");
        states.add(target);
        values.add(build(p, children));
        symbols.add(p.lhs());
    }

    /** Builds a parse-tree node, or evaluates the AST annotation in --ast mode. */
    private Node build(Production p, List<Node> children) {
        if (options.buildAst() && p.ast() != null) return eval(p.ast(), children);
        return Node.inner(g.name(p.lhs()), children);
    }

    private static Node eval(AstExpr e, List<Node> children) {
        return switch (e) {
            case AstExpr.ChildRef c -> children.get(c.index() - 1);
            case AstExpr.Ctor c -> {
                List<Node> kids = new ArrayList<>();
                for (AstExpr arg : c.args()) {
                    Node v = eval(arg, children);
                    if (c.name().equals("List") && !v.isLeaf() && v.label().equals("List")) {
                        kids.addAll(v.children()); // splice nested lists: flattens left recursion
                    } else {
                        kids.add(v);
                    }
                }
                yield Node.inner(c.name(), kids);
            }
        };
    }

    /**
     * Panic mode: returns the token index to resume at, or -1 to give up. On return the state
     * stack has been popped so that its top state has an action for that token.
     */
    private int recover(List<Token> tokens, int i) {
        BitSet sync = g.syncTerminals();
        while (true) {
            // 1. skip to a synchronizing token (or end of input)
            while (!tokens.get(i).isEof() && !sync.get(terminalOf(tokens.get(i)))) i++;
            int a = terminalOf(tokens.get(i));
            // 2. pop states until one can act on it
            int depth = states.size() - 1;
            while (depth >= 0 && !usable(table.action(states.get(depth), a))) depth--;
            if (depth >= 0) {
                while (states.size() - 1 > depth) {
                    states.remove(states.size() - 1);
                    values.remove(values.size() - 1);
                    symbols.remove(symbols.size() - 1);
                }
                return i;
            }
            // 3. nobody can use it: discard it and keep scanning
            if (tokens.get(i).isEof()) return -1;
            i++;
        }
    }

    private static boolean usable(Action a) {
        return a != null && !(a instanceof Action.Error);
    }

    private void report(Token tok, int a, int state, Action act) {
        String found = tok.isEof() ? "end of input" : g.name(a) + (g.name(a).startsWith("'") ? "" : " " + Token.quote(tok.text()));
        StringBuilder msg = new StringBuilder("unexpected ").append(found);
        if (act instanceof Action.Error) {
            msg.append(" (").append(g.name(a)).append(" is non-associative and cannot be chained)");
        } else {
            BitSet exp = table.expected(state);
            if (!exp.isEmpty()) {
                msg.append(", expected ").append(exp.cardinality() == 1 ? "" : "one of: ");
                boolean first = true;
                for (int t = exp.nextSetBit(0); t >= 0; t = exp.nextSetBit(t + 1)) {
                    if (!first) msg.append(", ");
                    msg.append(t == Grammar.EOF ? "end of input" : g.name(t));
                    first = false;
                }
            }
        }
        addError(tok, msg.toString());
    }

    private void addError(Token tok, String msg) {
        errors.add(Diagnostic.error(fileName, new Pos(tok.line(), tok.col()), msg));
    }

    private int terminalOf(Token t) {
        return g.terminalOfRule(t.rule());
    }

    private int top() {
        return states.get(states.size() - 1);
    }

    private String stackString() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < states.size(); k++) {
            if (k > 0) sb.append(' ').append(g.name(symbols.get(k))).append(' ');
            sb.append(states.get(k));
        }
        return sb.toString();
    }

    private static String inputString(List<Token> tokens, int i) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int k = i; k < tokens.size() && shown < 8; k++, shown++) {
            if (sb.length() > 0) sb.append(' ');
            Token t = tokens.get(k);
            sb.append(t.isEof() ? "$" : t.kind().startsWith("'") ? t.kind() : t.text());
        }
        if (tokens.size() - i > 8) sb.append(" ...");
        return sb.toString();
    }

    private String describe(Action act, Token tok) {
        return switch (act) {
            case Action.Shift s -> "shift " + s.state();
            case Action.Reduce r -> "reduce " + g.production(r.production()).show(g);
            case Action.Accept a -> "accept";
            case null, default -> "error";
        };
    }
}
