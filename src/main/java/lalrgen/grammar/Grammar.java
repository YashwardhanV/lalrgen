package lalrgen.grammar;

import lalrgen.spec.Assoc;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * An augmented context-free grammar with integer symbol ids.
 *
 * <ul>
 *   <li>Terminals: ids 0..terminalCount-1. Id 0 is the end marker {@code $}.</li>
 *   <li>Non-terminals: ids terminalCount..symbolCount-1. The first one is the augmented start S'.</li>
 *   <li>Production 0 is S' -> S.</li>
 * </ul>
 */
public final class Grammar {
    public static final int EOF = 0;

    private final List<Symbol> symbols;
    private final int terminalCount;
    private final List<Production> productions;
    private final List<List<Production>> byLhs;
    private final int[] terminalPrec;
    private final Assoc[] terminalAssoc;
    private final int[] terminalOfRule;
    private final BitSet syncTerminals;

    /**
     * @param terminalOfRule maps lexer rule index to terminal id (-1 for %skip rules)
     */
    public Grammar(List<Symbol> symbols, int terminalCount, List<Production> productions,
                   int[] terminalPrec, Assoc[] terminalAssoc, int[] terminalOfRule, BitSet syncTerminals) {
        this.symbols = List.copyOf(symbols);
        this.terminalCount = terminalCount;
        this.productions = List.copyOf(productions);
        this.terminalPrec = terminalPrec;
        this.terminalAssoc = terminalAssoc;
        this.terminalOfRule = terminalOfRule;
        this.syncTerminals = syncTerminals;
        List<List<Production>> by = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) by.add(new ArrayList<>());
        for (Production p : productions) by.get(p.lhs()).add(p);
        List<List<Production>> frozen = new ArrayList<>();
        for (List<Production> l : by) frozen.add(Collections.unmodifiableList(l));
        this.byLhs = Collections.unmodifiableList(frozen);
    }

    public int symbolCount() {
        return symbols.size();
    }

    public int terminalCount() {
        return terminalCount;
    }

    public int nonTerminalCount() {
        return symbols.size() - terminalCount;
    }

    public boolean isTerminal(int sym) {
        return sym < terminalCount;
    }

    public Symbol symbol(int id) {
        return symbols.get(id);
    }

    public String name(int id) {
        return symbols.get(id).name();
    }

    public List<Symbol> symbols() {
        return symbols;
    }

    /** Id of a symbol by name, or -1. */
    public int idOf(String name) {
        for (Symbol s : symbols) if (s.name().equals(name)) return s.id();
        return -1;
    }

    /** The augmented start symbol S'. */
    public int augmentedStart() {
        return terminalCount;
    }

    /** The user's start symbol S (right side of production 0). */
    public int start() {
        return productions.get(0).rhs()[0];
    }

    public List<Production> productions() {
        return productions;
    }

    public Production production(int id) {
        return productions.get(id);
    }

    public List<Production> productionsOf(int nonTerminal) {
        return byLhs.get(nonTerminal);
    }

    /** Precedence level of a terminal (0 = none). */
    public int precedence(int terminal) {
        return terminalPrec[terminal];
    }

    public Assoc assoc(int terminal) {
        return terminalAssoc[terminal];
    }

    /** Terminal id for a lexer rule index, or -1 if that rule is a %skip rule. */
    public int terminalOfRule(int rule) {
        return rule < 0 ? EOF : terminalOfRule[rule];
    }

    /** Synchronizing tokens for panic-mode error recovery. */
    public BitSet syncTerminals() {
        return (BitSet) syncTerminals.clone();
    }

    /** Grammar listing, one production per line, as printed by the CLI. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Production p : productions) {
            sb.append(String.format("%4d  %s%n", p.id(), p.show(this)));
        }
        return sb.toString();
    }
}
