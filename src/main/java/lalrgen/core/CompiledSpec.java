package lalrgen.core;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.lexer.LexerTable;
import lalrgen.lr.LRAutomaton;
import lalrgen.lr.Mode;
import lalrgen.lr.ParseTable;
import lalrgen.lr.TableBuilder;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.SpecFile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything generated from one spec file: the lexer, the grammar with its FIRST/FOLLOW sets,
 * and (built on demand and cached) the parse table for each mode.
 */
public final class CompiledSpec {
    private final SpecFile spec;
    private final LexerTable lexer;
    private final Grammar grammar;
    private final GrammarAnalysis analysis;
    private final List<Diagnostic> warnings;
    private final Map<Mode, ParseTable> tables = new EnumMap<>(Mode.class);
    private LRAutomaton lr0;
    private LRAutomaton lr1;

    public CompiledSpec(SpecFile spec, LexerTable lexer, Grammar grammar, GrammarAnalysis analysis,
                        List<Diagnostic> warnings) {
        this.spec = spec;
        this.lexer = lexer;
        this.grammar = grammar;
        this.analysis = analysis;
        this.warnings = warnings;
    }

    public SpecFile spec() {
        return spec;
    }

    public LexerTable lexer() {
        return lexer;
    }

    public Grammar grammar() {
        return grammar;
    }

    public GrammarAnalysis analysis() {
        return analysis;
    }

    public List<Diagnostic> warnings() {
        return warnings;
    }

    public synchronized LRAutomaton lr0() {
        if (lr0 == null) lr0 = lalrgen.lr.AutomatonBuilder.buildLR0(grammar, analysis);
        return lr0;
    }

    public synchronized LRAutomaton lr1() {
        if (lr1 == null) lr1 = lalrgen.lr.AutomatonBuilder.buildLR1(grammar, analysis);
        return lr1;
    }

    /** The automaton a mode's table is built from: LR(0) for SLR, LR(1), or merged LALR. */
    public synchronized LRAutomaton automaton(Mode mode) {
        return switch (mode) {
            case SLR -> lr0();
            case LR1 -> lr1();
            case LALR -> table(Mode.LALR).automaton();
        };
    }

    public synchronized ParseTable table(Mode mode) {
        return tables.computeIfAbsent(mode, m -> switch (m) {
            case SLR -> TableBuilder.build(grammar, analysis, lr0(), Mode.SLR);
            case LR1 -> TableBuilder.build(grammar, analysis, lr1(), Mode.LR1);
            case LALR -> TableBuilder.build(grammar, analysis, lalrgen.lr.LalrMerger.merge(lr1()), Mode.LALR);
        });
    }
}
