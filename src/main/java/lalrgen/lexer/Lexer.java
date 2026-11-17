package lalrgen.lexer;

import lalrgen.automata.DFA;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer runtime: maximal munch over the minimized DFA.
 *
 * <p>From the current position, walk the DFA as far as it goes, remembering the last position
 * where the state was accepting. When the DFA gets stuck, emit the token for that last accepting
 * position and restart from there. So {@code >=} is one token, not {@code >} then {@code =}.
 * Ties between rules were already settled inside the DFA by priority.
 */
public final class Lexer {
    public static final int DEFAULT_MAX_ERRORS = 20;

    private Lexer() {}

    /** Result of lexing: the tokens (always ending with $) and any errors. */
    public record Result(List<Token> tokens, List<Diagnostic> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Result tokenize(LexerTable table, String fileName, String input) {
        return tokenize(table, fileName, input, DEFAULT_MAX_ERRORS);
    }

    public static Result tokenize(LexerTable table, String fileName, String input, int maxErrors) {
        DFA dfa = table.minDfa();
        int[] cps = input.codePoints().toArray();
        List<Token> tokens = new ArrayList<>();
        List<Diagnostic> errors = new ArrayList<>();
        int pos = 0;
        int line = 1;
        int col = 1;
        // a run of consecutive unmatched characters is reported as one error
        StringBuilder bad = new StringBuilder();
        Pos badPos = null;
        while (pos < cps.length) {
            int state = 0;
            int lastRule = -1;
            int lastEnd = -1;
            int j = pos;
            while (j < cps.length) {
                int next = dfa.next(state, cps[j]);
                if (next < 0) break;
                state = next;
                j++;
                if (dfa.accept(state) >= 0) {
                    lastRule = dfa.accept(state);
                    lastEnd = j;
                }
            }
            if (lastRule < 0) {
                if (badPos == null) badPos = new Pos(line, col);
                bad.appendCodePoint(cps[pos]);
                boolean newline = cps[pos] == '\n';
                if (newline) {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
                pos++;
                if (newline && !flush(fileName, errors, bad, badPos, maxErrors)) break;
                if (newline) badPos = null;
                continue;
            }
            if (badPos != null) {
                if (!flush(fileName, errors, bad, badPos, maxErrors)) break;
                badPos = null;
            }
            TokenRule rule = table.rules().get(lastRule);
            String text = new String(cps, pos, lastEnd - pos);
            if (!rule.isSkip()) tokens.add(new Token(lastRule, rule.name(), text, line, col));
            for (int k = pos; k < lastEnd; k++) {
                if (cps[k] == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            pos = lastEnd;
        }
        if (badPos != null) flush(fileName, errors, bad, badPos, maxErrors);
        tokens.add(Token.eof(line, col));
        return new Result(List.copyOf(tokens), List.copyOf(errors));
    }

    /** Records one "unexpected character(s)" error; returns false once the error limit is hit. */
    private static boolean flush(String fileName, List<Diagnostic> errors, StringBuilder bad, Pos at, int maxErrors) {
        if (bad.isEmpty()) return true;
        String text = bad.toString().strip();
        if (text.isEmpty()) text = bad.toString();
        bad.setLength(0);
        if (errors.size() >= maxErrors) return false;
        String shown = Token.quote(text);
        errors.add(Diagnostic.error(fileName, at, (text.codePointCount(0, text.length()) == 1
                ? "unexpected character " : "unexpected characters ") + shown));
        if (errors.size() == maxErrors) {
            errors.add(Diagnostic.error(fileName, at, "too many lexical errors, stopping"));
            return false;
        }
        return true;
    }
}
