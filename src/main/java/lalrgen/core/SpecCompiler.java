package lalrgen.core;

import lalrgen.grammar.Grammar;
import lalrgen.grammar.GrammarAnalysis;
import lalrgen.grammar.Production;
import lalrgen.grammar.Symbol;
import lalrgen.lexer.LexerTable;
import lalrgen.lexer.TokenRule;
import lalrgen.regex.RegexNode;
import lalrgen.regex.RegexParser;
import lalrgen.regex.RegexSyntaxException;
import lalrgen.spec.Alt;
import lalrgen.spec.Assoc;
import lalrgen.spec.AstExpr;
import lalrgen.spec.Diagnostic;
import lalrgen.spec.Pos;
import lalrgen.spec.PrecDecl;
import lalrgen.spec.RuleDef;
import lalrgen.spec.SpecException;
import lalrgen.spec.SpecFile;
import lalrgen.spec.SpecParser;
import lalrgen.spec.SymRef;
import lalrgen.spec.TokenDef;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a parsed {@link SpecFile} into a lexer table and an augmented grammar, checking every
 * semantic rule of the spec format (undefined symbols, duplicate tokens, bad regexes, empty-match
 * regexes, non-productive and unreachable non-terminals, %prec without precedence, bad $n).
 */
public final class SpecCompiler {
    private final SpecFile spec;
    private final List<Diagnostic> errors = new ArrayList<>();
    private final List<Diagnostic> warnings = new ArrayList<>();

    private SpecCompiler(SpecFile spec) {
        this.spec = spec;
        warnings.addAll(spec.warnings());
    }

    /** Parses and compiles spec text. Throws {@link SpecException} on any error. */
    public static CompiledSpec compile(String fileName, String text) {
        return compile(SpecParser.parse(fileName, text));
    }

    public static CompiledSpec compile(SpecFile spec) {
        return new SpecCompiler(spec).run();
    }

    private CompiledSpec run() {
        String file = spec.fileName();

        // 1. Literal tokens, in order of first appearance in the rules.
        Map<String, SymRef> literals = new LinkedHashMap<>();
        for (RuleDef r : spec.rules()) {
            for (Alt a : r.alts()) {
                for (SymRef s : a.symbols()) if (s.literal()) literals.putIfAbsent(s.name(), s);
            }
        }

        // 2. Named tokens: check duplicates.
        Map<String, TokenDef> named = new LinkedHashMap<>();
        for (TokenDef t : spec.tokens()) {
            if (t.kind() == TokenDef.Kind.SKIP) continue;
            TokenDef prev = named.putIfAbsent(t.name(), t);
            if (prev != null) error(t.pos(), "duplicate token '" + t.name() + "' (first defined at " + prev.pos() + ")");
        }

        // 3. Lexer rules: literals first (highest priority), then %token / %skip in file order.
        List<TokenRule> rules = new ArrayList<>();
        for (SymRef lit : literals.values()) {
            rules.add(new TokenRule(rules.size(), lit.name(), TokenRule.Kind.LITERAL,
                    RegexNode.literal(lit.text()), lit.text(), lit.pos()));
        }
        for (TokenDef t : spec.tokens()) {
            RegexNode re;
            try {
                re = RegexParser.parse(t.regex());
            } catch (RegexSyntaxException e) {
                Pos p = new Pos(t.regexPos().line(), t.regexPos().col() + e.offset());
                error(p, "invalid regex for " + displayTokenName(t) + ": " + e.getMessage());
                continue;
            }
            if (RegexNode.nullable(re)) {
                error(t.regexPos(), "regex for " + displayTokenName(t)
                        + " matches the empty string, which would make the lexer loop forever");
                continue;
            }
            TokenRule.Kind kind = t.kind() == TokenDef.Kind.SKIP ? TokenRule.Kind.SKIP : TokenRule.Kind.REGEX;
            rules.add(new TokenRule(rules.size(), t.name(), kind, re, t.regex(), t.pos()));
        }

        // 4. Symbols: $, literals, named tokens; then non-terminals in order of definition.
        List<Symbol> symbols = new ArrayList<>();
        Map<String, Integer> idOf = new HashMap<>();
        addSymbol(symbols, idOf, "$", true);
        for (String lit : literals.keySet()) addSymbol(symbols, idOf, lit, true);
        for (String name : named.keySet()) addSymbol(symbols, idOf, name, true);
        int terminalCount = symbols.size();

        Map<String, RuleDef> firstRuleOf = new LinkedHashMap<>();
        for (RuleDef r : spec.rules()) {
            if (named.containsKey(r.lhs())) {
                error(r.pos(), "'" + r.lhs() + "' is declared as a %token and cannot also be a rule name");
                continue;
            }
            firstRuleOf.putIfAbsent(r.lhs(), r);
        }
        if (firstRuleOf.isEmpty()) {
            if (errors.isEmpty()) error(Pos.NONE, "no grammar rules");
            throw new SpecException(errors);
        }

        // Start symbol.
        String startName;
        Pos startPos;
        if (spec.start() != null) {
            startName = spec.start().name();
            startPos = spec.start().pos();
            if (!firstRuleOf.containsKey(startName)) {
                error(startPos, "%start symbol '" + startName + "' has no rules");
                throw new SpecException(errors);
            }
        } else {
            startName = firstRuleOf.keySet().iterator().next();
            startPos = firstRuleOf.get(startName).pos();
        }
        addSymbol(symbols, idOf, startName + "'", false); // augmented start S'
        for (String nt : firstRuleOf.keySet()) addSymbol(symbols, idOf, nt, false);

        // 5. Precedence table.
        int[] prec = new int[terminalCount];
        Assoc[] assoc = new Assoc[terminalCount];
        Map<String, PrecDecl> pseudoPrec = new HashMap<>();
        Map<String, Pos> precSeen = new HashMap<>();
        for (PrecDecl d : spec.precs()) {
            for (SymRef s : d.names()) {
                Pos prev = precSeen.putIfAbsent(s.name(), s.pos());
                if (prev != null) {
                    error(s.pos(), "precedence of " + s.name() + " is already declared at " + prev);
                    continue;
                }
                Integer id = idOf.get(s.name());
                if (id != null && id < terminalCount) {
                    prec[id] = d.level();
                    assoc[id] = d.assoc();
                } else if (id != null) {
                    error(s.pos(), "'" + s.name() + "' is a non-terminal; precedence applies to tokens only");
                } else {
                    pseudoPrec.put(s.name(), d); // e.g. UMINUS, used only via %prec
                }
            }
        }

        // 6. Productions.
        List<Production> prods = new ArrayList<>();
        int startId = idOf.get(startName);
        prods.add(new Production(0, terminalCount, new int[] {startId}, 0, null, null, startPos));
        boolean[] usedTerminal = new boolean[terminalCount];
        for (RuleDef r : spec.rules()) {
            Integer lhs = idOf.get(r.lhs());
            if (lhs == null || lhs < terminalCount) continue; // already reported
            for (Alt a : r.alts()) {
                int[] rhs = new int[a.symbols().size()];
                boolean ok = true;
                for (int i = 0; i < rhs.length; i++) {
                    SymRef s = a.symbols().get(i);
                    Integer id = idOf.get(s.name());
                    if (id == null) {
                        if (pseudoPrec.containsKey(s.name())) {
                            error(s.pos(), "'" + s.name() + "' is only a precedence name; it has no %token definition");
                        } else {
                            error(s.pos(), "undefined symbol '" + s.name() + "'");
                        }
                        ok = false;
                        continue;
                    }
                    rhs[i] = id;
                    if (id < terminalCount) usedTerminal[id] = true;
                }
                int level = 0;
                Assoc as = null;
                if (a.prec() != null) {
                    Integer id = idOf.get(a.prec().name());
                    if (id != null && id < terminalCount && prec[id] > 0) {
                        level = prec[id];
                        as = assoc[id];
                    } else if (pseudoPrec.containsKey(a.prec().name())) {
                        level = pseudoPrec.get(a.prec().name()).level();
                        as = pseudoPrec.get(a.prec().name()).assoc();
                    } else {
                        error(a.prec().pos(), "%prec " + a.prec().name() + ": no precedence declared for it");
                        ok = false;
                    }
                } else if (ok) {
                    for (int i = rhs.length - 1; i >= 0; i--) {
                        if (rhs[i] < terminalCount && prec[rhs[i]] > 0) {
                            level = prec[rhs[i]];
                            as = assoc[rhs[i]];
                            break;
                        }
                    }
                }
                if (a.ast() != null) checkAst(a.ast(), rhs.length);
                if (ok) prods.add(new Production(prods.size(), lhs, rhs, level, as, a.ast(), a.pos()));
            }
        }

        // 7. Sync tokens for error recovery.
        BitSet sync = new BitSet();
        if (spec.sync().isEmpty()) {
            for (String def : List.of("';'", "'}'")) {
                Integer id = idOf.get(def);
                if (id != null && id < terminalCount) sync.set(id);
            }
        } else {
            for (SymRef s : spec.sync()) {
                Integer id = idOf.get(s.name());
                if (id == null || id >= terminalCount) error(s.pos(), "%sync: '" + s.name() + "' is not a token");
                else sync.set(id);
            }
        }

        if (!errors.isEmpty()) throw new SpecException(errors);

        // 8. Map lexer rules to terminals.
        int[] terminalOfRule = new int[rules.size()];
        for (TokenRule r : rules) terminalOfRule[r.index()] = r.isSkip() ? -1 : idOf.get(r.name());

        Grammar g = new Grammar(symbols, terminalCount, prods, prec, assoc, terminalOfRule, sync);
        GrammarAnalysis analysis = new GrammarAnalysis(g);

        // 9. Grammar sanity checks.
        for (int a = terminalCount + 1; a < g.symbolCount(); a++) {
            Pos p = firstRuleOf.get(g.name(a)).pos();
            if (!analysis.productive(a)) {
                error(p, "non-terminal '" + g.name(a) + "' cannot derive any string of tokens (every rule for it recurses forever)");
            } else if (!analysis.reachable(a)) {
                warning(p, "non-terminal '" + g.name(a) + "' is unreachable from the start symbol '" + startName + "'");
            }
        }
        for (String name : named.keySet()) {
            if (!usedTerminal[idOf.get(name)]) {
                warning(named.get(name).pos(), "token " + name + " is never used in the grammar");
            }
        }
        if (!errors.isEmpty()) throw new SpecException(errors);

        // 10. Lexer.
        LexerTable lexer = LexerTable.generate(rules);
        for (int[] sh : lexer.shadowedRules()) {
            TokenRule r = rules.get(sh[0]);
            String by = sh[1] >= 0 ? rules.get(sh[1]).name() : "another rule";
            warning(r.pos(), "token " + r.name() + " can never be matched: every string it matches is claimed by "
                    + by + ", which has higher priority (move it earlier, or use a literal)");
        }
        return new CompiledSpec(spec, lexer, g, analysis, List.copyOf(warnings));
    }

    private void checkAst(AstExpr e, int n) {
        switch (e) {
            case AstExpr.ChildRef c -> {
                if (c.index() > n) error(c.pos(), "$" + c.index() + " is out of range: this alternative has " + n + " symbol(s)");
            }
            case AstExpr.Ctor c -> {
                for (AstExpr a : c.args()) checkAst(a, n);
            }
        }
    }

    private static String displayTokenName(TokenDef t) {
        return t.kind() == TokenDef.Kind.SKIP ? "%skip" : "token " + t.name();
    }

    private static void addSymbol(List<Symbol> symbols, Map<String, Integer> idOf, String name, boolean terminal) {
        int id = symbols.size();
        symbols.add(new Symbol(id, name, terminal));
        idOf.put(name, id);
    }

    private void error(Pos p, String msg) {
        errors.add(Diagnostic.error(spec.fileName(), p, msg));
    }

    private void warning(Pos p, String msg) {
        warnings.add(Diagnostic.warning(spec.fileName(), p, msg));
    }
}
