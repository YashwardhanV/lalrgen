# spec.md: LALRGen, a Lex/Yacc clone in Java

## 1. What we are building

LALRGen is a command-line tool that reads one spec file containing token definitions (regular expressions) and a context-free grammar, then:

1. Builds a **lexer** from the token regexes: regex, then Thompson NFA, then subset-construction DFA, then minimized DFA.
2. Builds an **LR parser table** from the grammar: SLR(1), canonical LR(1) and LALR(1).
3. Reports **conflicts** clearly, with the shortest input prefix that reaches the conflict.
4. **Runs** the generated lexer and parser on an input file and prints tokens, a parse trace and a parse tree or AST.
5. **Exports visualizations** (Graphviz DOT) of the NFA, the DFA and the LR item-set automaton.

This is an academic project (Theory of Computation plus Compiler Design). Correctness and explainability matter more than raw speed. The author must be able to explain every algorithm in a viva, so the code must be readable and the algorithms must be implemented by hand.

## 2. Hard constraints for the implementer

- **Language:** Java 21. Use records, sealed interfaces and pattern-matching `switch` where they make the code clearer.
- **Build:** Maven. A single runnable fat JAR via `mvn package` (maven-shade or maven-assembly plugin).
- **Tests:** JUnit 5. Every milestone ships with tests, and `mvn test` must pass before moving to the next milestone.
- **No parsing or automata libraries.** Do not use ANTLR, JavaCC, JFlex, CUP, Beaver or similar. Do not use `java.util.regex` inside the engine. `java.util.regex` is allowed only in tests, as an oracle to cross-check our regex engine.
- **No runtime dependencies** other than the JDK. Test-scope dependencies (JUnit, optionally jqwik for property tests) are fine. Hand-roll CLI argument parsing; it is small.
- **Deterministic output.** State numbering, table layout and conflict ordering must be identical across runs (BFS order, symbols sorted by a fixed order). Tests depend on this.
- **Build incrementally.** Follow the milestones in section 10 in order. Do not start a milestone until the previous one is green.
- **Explain as you go.** Maintain `docs/ALGORITHMS.md` (section 11) alongside the code.

## 3. Repository layout

```
lalrgen/
  pom.xml
  spec.md
  README.md
  docs/
    ALGORITHMS.md
    SPEC_FORMAT.md
  examples/
    calc.lg
    json.lg
    minic.lg
    dangling_else.lg
    not_slr.lg          # LALR but not SLR
    not_lalr.lg         # LR(1) but not LALR
  src/main/java/lalrgen/
    cli/          Main, command dispatch, argument parsing
    spec/         Spec-file lexer and parser, SpecFile model
    regex/        Regex parser, RegexNode AST
    automata/     NFA, DFA, Thompson, SubsetConstruction, Minimizer, CharClasses
    lexer/        LexerTable, LexerRuntime (maximal munch), Token
    grammar/      Symbol, Production, Grammar, NullableFirstFollow
    lr/           LR0Item, LR1Item, ItemSet, Automaton builders, TableBuilder,
                  Conflict, ConflictResolver, ParseTable
    runtime/      ParserDriver, ParseTree, AstBuilder, ParseError
    viz/          DotExporter, TableFormatter, JsonWriter
  src/test/java/lalrgen/...   mirrors main packages
```

The `.lg` extension is the spec-file extension.

## 4. Spec file format

A spec file has a declarations section, a `%%` separator, then a rules section.

### 4.1 Example

```
# calc.lg: arithmetic calculator
%token NUM   /[0-9]+(\.[0-9]+)?/
%token ID    /[a-zA-Z_][a-zA-Z0-9_]*/
%skip        /[ \t\r\n]+/
%skip        /#[^\n]*/

%left  '+' '-'
%left  '*' '/'
%right UMINUS
%start expr

%%

expr : expr '+' expr          -> Add($1, $3)
     | expr '-' expr          -> Sub($1, $3)
     | expr '*' expr          -> Mul($1, $3)
     | expr '/' expr          -> Div($1, $3)
     | '-' expr %prec UMINUS  -> Neg($2)
     | '(' expr ')'           -> $2
     | NUM                    -> Num($1)
     | ID                     -> Var($1)
     ;
```

### 4.2 Declarations

| Directive | Meaning |
|---|---|
| `%token NAME /regex/` | Defines a token type. Regex is delimited by `/`. A literal `/` inside the regex is written `\/`. |
| `%skip /regex/` | Text matched here is consumed and discarded (whitespace, comments). |
| `%left A B ...` | Declares left-associative tokens. Each later `%left`/`%right`/`%nonassoc` line has **higher** precedence than earlier lines. |
| `%right A B ...` | Right-associative. |
| `%nonassoc A B ...` | Non-associative (`a < b < c` is an error). |
| `%start NAME` | Start non-terminal. If absent, use the left side of the first rule. |

Precedence directives may name `%token` names, quoted literals, or pseudo-tokens that appear only in `%prec` (like `UMINUS`).

### 4.3 Rules

```
rules   := rule*
rule    := NAME ':' alt ('|' alt)* ';'
alt     := symbol* ('%prec' NAME)? ('->' ast)?
symbol  := NAME | QUOTED_LITERAL
```

- `NAME` that matches a `%token` is a terminal; any other `NAME` is a non-terminal. A `NAME` that is neither a token nor defined by a rule is an error.
- `'literal'` in a rule (like `'+'`, `'if'`, `'=='`) automatically defines an **exact-match literal token**. Literal tokens have higher priority than every `%token` regex. This is how keywords beat `ID`.
- An empty alternative (nothing before `|` or `;`) or the keyword `%empty` means epsilon.
- Comments start with `#` and run to end of line (outside regexes and quoted literals).

### 4.4 AST annotations (milestone 7)

```
ast   := '$' INT                      # pass through child N (1-based)
       | NAME '(' args? ')'           # build node NAME with children
args  := ast (',' ast)*
```

If an alternative has no `->`, the driver produces a generic parse-tree node labeled with the non-terminal name, with all children.

### 4.5 Spec errors

All spec errors report `file:line:col` and a message: undefined symbol, duplicate token name, unreachable non-terminal (warning), non-terminal that derives no terminal string (error), invalid regex, regex that matches the empty string in a `%token` (error, since it would make the lexer loop).

## 5. Part A: lexer generator

### 5.1 Regex syntax supported

| Syntax | Meaning |
|---|---|
| `a` | literal character |
| `\n \t \r \\ \/ \. \* \+ \? \| \( \) \[ \] \{ \} \^ \-` | escapes |
| `.` | any character except `\n` |
| `[abc]`, `[a-z0-9_]` | character class |
| `[^...]` | negated class |
| `AB` | concatenation |
| `A\|B` | union |
| `A*`, `A+`, `A?` | star, plus, optional |
| `A{m}`, `A{m,}`, `A{m,n}` | bounded repetition, expanded at parse time |
| `(A)` | grouping |

Precedence, highest first: postfix operators, concatenation, union.

Out of scope: backreferences, lookahead and lookbehind, anchors, named classes like `\d` (optional stretch: `\d \w \s` as shorthand). These are not regular or not needed.

The regex parser is a hand-written recursive-descent parser producing a sealed `RegexNode` hierarchy: `CharSet`, `Concat`, `Union`, `Star`, `Epsilon`. `+`, `?` and `{m,n}` are desugared into these.

### 5.2 Character classes (alphabet compression)

Do not create one DFA edge per Unicode character. Represent edge labels as **sets of code-point intervals**. Before subset construction, compute the partition of the code-point space into disjoint intervals such that every regex `CharSet` is a union of partition blocks (`CharClasses`). DFA transitions are then indexed by block ID. This keeps tables small and makes `[^\n]` cheap. Operate on Unicode code points, not Java `char`s.

### 5.3 Thompson construction

Build an NFA fragment per `RegexNode`, exactly per Thompson's rules (one start, one accept per fragment, ε-edges for union, concat and star). For the lexer, create one global start state with ε-edges to the start of each token's NFA (literal tokens, `%token`s, `%skip`s). Each fragment's accept state is tagged with `(tokenType, priority)`:

- Literal tokens: highest priority, in order of first appearance.
- `%token` and `%skip`: in file order.

Lower priority number means wins.

### 5.4 Subset construction

Standard algorithm: DFA start = `ε-closure({nfaStart})`. Worklist over DFA states, for each character block compute `ε-closure(move(S, block))`. Use `BitSet` for NFA-state sets and a `HashMap<BitSet, Integer>` (or an immutable key wrapper) to dedupe. An accepting DFA state takes the best-priority token among its NFA accept states. Record when a DFA state contains several accept tags; the CLI can report "token X shadows token Y" warnings (for example, a `%token` that can never win).

### 5.5 Minimization

Use Hopcroft's algorithm (or Moore's partition refinement; Hopcroft preferred). **Critical:** the initial partition is **one block per token type plus one block for non-accepting states**, not just accepting vs non-accepting. Merging accepting states of different tokens would silently break the lexer. Include a dedicated test for this.

Remove the dead state from the printed table but handle it implicitly (missing transition means dead).

### 5.6 Lexer runtime

- **Maximal munch:** advance through the DFA as far as possible, remembering the last position where the state was accepting. On a dead transition, emit the token for the last accepting position and restart from there.
- Ties are already resolved by priority in the DFA.
- `%skip` matches are consumed silently.
- Track line and column for every token.
- On no match: report `line:col: unexpected character 'x'`, skip one code point, and continue, collecting up to N errors (default 20).
- Emit a final `$` (EOF) token.

## 6. Part B: parser generator

### 6.1 Grammar model

- Augment the grammar with `S' -> S` (production 0).
- Symbols get stable integer IDs: terminals first (`$` = 0, then literal tokens, then `%token`s in file order), then non-terminals in order of first definition.
- Productions are numbered in file order after production 0.

### 6.2 Nullable, FIRST, FOLLOW

Compute with fixed-point iteration until no set changes. Use `BitSet` over terminal IDs.

- `nullable(A)`
- `FIRST(X)` for every symbol, and a helper `FIRST(sequence)` that handles nullable prefixes
- `FOLLOW(A)`, with `$` in `FOLLOW(start)`

CLI command `sets` prints all three.

### 6.3 LR(0) automaton and SLR(1)

- `record LR0Item(int production, int dot)`
- `closure(I)`: for every item with the dot before non-terminal B, add `B -> . γ` for all B productions, until no change.
- `goto(I, X)`: advance the dot over X, then closure.
- Canonical collection via BFS from `closure({S' -> . S})`, iterating symbols in ID order, so state numbers are deterministic.
- SLR table: shift on `goto` for terminals; reduce `A -> α .` on every terminal in `FOLLOW(A)`; accept on `S' -> S .` with `$`; `goto` table for non-terminals.

### 6.4 Canonical LR(1)

- `record LR1Item(int production, int dot, int lookahead)`. Store item sets grouped as core plus lookahead `BitSet` per core item for efficiency; that is equivalent to the set of `LR1Item`s.
- LR(1) closure: for `[A -> α . B β, a]` add `[B -> . γ, b]` for every `b` in `FIRST(β a)`.
- Canonical collection by BFS, as in 6.3.
- Table: reduce `A -> α .` only on that item's lookaheads.

### 6.5 LALR(1)

Build the canonical LR(1) collection, then **merge states with identical cores** (same set of LR(0) items), unioning lookaheads, and remap goto edges. This is the required method because it is the easiest to explain.

Optional stretch: implement DeRemer and Pennello (or the dragon-book propagation method) and assert that both methods produce identical tables on every example grammar.

The table builder takes a mode: `slr`, `lr1` or `lalr` (default `lalr`).

### 6.6 Conflict resolution (Yacc rules)

When a table cell receives two actions:

**Shift/reduce** between shifting token `t` and reducing production `p`:

1. Precedence of `p` = its `%prec` token if given, else the precedence of the **rightmost terminal** in its right-hand side.
2. If both `t` and `p` have precedence:
   - higher precedence wins;
   - equal precedence: `%left` means reduce, `%right` means shift, `%nonassoc` means the cell becomes an explicit error.
   - Resolved silently (but listed under `--verbose`).
3. Otherwise: choose **shift**, and report the conflict as a warning.

**Reduce/reduce:** choose the production that appears first in the file, and report it as a warning.

**Report format** for each unresolved conflict:

```
Conflict in state 7 on lookahead 'else' (shift/reduce)
  Shift:   stmt -> 'if' expr 'then' stmt . 'else' stmt
  Reduce:  stmt -> 'if' expr 'then' stmt .
  Example: after reading  'if' expr 'then' 'if' expr 'then' stmt   with next token 'else'
  Chosen:  shift
```

The example prefix is the **shortest symbol sequence** from state 0 to the conflict state, found by BFS over the goto graph (a viable prefix). Expand non-terminals in that prefix to a shortest terminal string when `--expand` is given (precompute the shortest terminal derivation of each non-terminal by fixed-point).

Exit code: 0 if no unresolved conflicts, 2 if warnings, 1 on errors. `--strict` makes any unresolved conflict an error.

### 6.7 Parse table output

- Human-readable aligned text table: rows = states; ACTION columns = terminals; GOTO columns = non-terminals. Cells: `s5`, `r3`, `acc`, blank.
- JSON (`--format json`) containing symbols, productions, lexer DFA (blocks, transitions, accepting tags), ACTION and GOTO tables. The JSON must be enough to run a parser without re-reading the spec.

## 7. Runtime driver

Standard LR driver:

```
stack = [0]
loop:
  s = top(stack); a = current token
  switch ACTION[s][a]:
    shift t   -> push t; advance
    reduce p  -> pop |rhs(p)| states; push GOTO[top][lhs(p)]; build tree node
    accept    -> done
    error     -> report and recover or stop
```

- Builds a **parse tree** by default, or an **AST** if annotations exist (section 4.4).
- **Errors:** report `line:col: unexpected 'X', expected one of: a, b, c`. The expected set is every terminal with a non-error ACTION in the current state.
- **Error recovery (milestone 8, stretch):** panic mode. Pop states until one has a shift on a synchronizing token (`;` or `}` by default, configurable with `%sync`), discard input until that token, and continue. Cap at 20 errors.
- **Trace mode:** `--trace` prints every step as `stack | remaining input | action`.

## 8. CLI

```
lalrgen check   spec.lg                     # validate spec, print conflicts
lalrgen sets    spec.lg                     # nullable, FIRST, FOLLOW
lalrgen tables  spec.lg [--mode slr|lr1|lalr] [--format text|json] [-o file]
lalrgen lex     spec.lg input.txt           # print token stream
lalrgen parse   spec.lg input.txt [--mode ...] [--trace] [--ast|--tree]
lalrgen dot     spec.lg --what nfa|dfa|mindfa|lr0|lr1|lalr [-o file.dot]
lalrgen compare spec.lg                     # SLR vs LR(1) vs LALR: state counts and conflicts
```

Global flags: `--verbose`, `--strict`, `--expand`. `lalrgen --help` and `lalrgen <cmd> --help` print usage.

`compare` is important for the demo. It prints a small table such as:

```
mode   states  s/r  r/r
slr    12      1    0
lr1    14      0    0
lalr   12      0    0
```

## 9. Visualization (DOT)

- NFA: ε-edges dashed, accept states as double circles labeled with the token name.
- DFA and minimized DFA: edges labeled with compact interval notation (`a-z`, `0-9`, `[^\n]`), merging parallel edges into one label.
- LR automata: each node lists its items (with lookaheads for LR(1) and LALR), edges labeled with the grammar symbol, conflict states outlined in red.
- Only the DOT text is produced. Rendering (`dot -Tsvg`) is left to Graphviz, and the README shows the command.

## 10. Milestones and acceptance criteria

Each milestone must end with `mvn test` passing and a short update to `docs/ALGORITHMS.md`.

### M1: Project skeleton, spec parser
- Maven project, CLI skeleton, `SpecFile` model.
- Spec lexer and parser for section 4 (without AST annotations).
- Tests: valid specs parse; each error in 4.5 is reported with the right line and column.

### M2: Regex and Thompson NFA
- Regex parser with full section 5.1 syntax, `CharClasses`, Thompson construction, NFA simulation (for testing).
- Tests: for at least 30 regexes, NFA simulation agrees with `java.util.regex` `matches()` on random and edge-case strings (property test with at least 500 strings per regex).

### M3: DFA, minimization, lexer runtime
- Subset construction, Hopcroft minimization, maximal-munch runtime, `lex` command, `dot --what nfa|dfa|mindfa`.
- Tests:
  - `(a|b)*abb` minimizes to exactly **4** states (dead state excluded).
  - Minimized DFA accepts exactly the same strings as the unminimized DFA (property test).
  - Keyword test: with literal `'if'` and `%token ID /[a-z]+/`, input `if iffy` gives `'if' ID`.
  - Maximal munch: with `'>'` and `'>='`, input `>=>` gives `'>=' '>'`.
  - Token-type partition test: two tokens with structurally identical tails must not merge.
  - Line and column tracking, unexpected-character errors.

### M4: Grammar analysis and SLR end to end
- Grammar model, nullable, FIRST, FOLLOW, LR(0) collection, SLR table, driver, `sets`, `tables --mode slr`, `parse`, `--trace`.
- Tests:
  - Dragon-book expression grammar (`E -> E + T | T`, `T -> T * F | F`, `F -> ( E ) | id`): **12** LR(0) states, no SLR conflicts; FOLLOW(E) = { `+`, `)`, `$` }; `id + id * id` parses with the correct tree shape (`*` deeper than `+`).
  - FIRST and FOLLOW checked against hand-computed sets for a grammar with nullable non-terminals.
  - **First end-to-end demo works here.** Do not proceed until it does.

### M5: Canonical LR(1) and LALR(1)
- LR(1) closure and collection, LALR merge, `--mode lr1|lalr`, `compare`, `dot --what lr0|lr1|lalr`.
- Tests:
  - Grammar `S -> C C`, `C -> c C | d`: **10** LR(1) states, **7** LALR states.
  - `not_slr.lg` (`S -> L = R | R`, `L -> * R | id`, `R -> L`): SLR has a shift/reduce conflict on `=`; LR(1) and LALR have none.
  - `not_lalr.lg` (`S -> a A d | b B d | a B e | b A e`, `A -> c`, `B -> c`): LR(1) has no conflicts; LALR has a **reduce/reduce** conflict introduced by merging.
  - Every LALR table parses exactly the same accepted and rejected inputs as the LR(1) table on all conflict-free example grammars (property test on generated sentences plus random token strings).

### M6: Precedence, associativity, conflict reports
- Section 6.6 in full, counterexample prefixes, `--expand`, `--strict`, exit codes.
- Tests:
  - `calc.lg` with ambiguous `expr` rules builds with **zero** unresolved conflicts thanks to `%left` and `%right`, and `1 - 2 - 3` parses as `(1 - 2) - 3`, `2 ^ 3 ^ 2` (if added as `%right`) as `2 ^ (3 ^ 2)`, `-2 * 3` with `%prec UMINUS` as `(-2) * 3`.
  - `dangling_else.lg` without precedence: exactly one shift/reduce conflict reported with an example prefix; default shift attaches `else` to the nearest `if`.
  - `%nonassoc '<'`: `a < b < c` is a parse error.

### M7: AST annotations and example grammars
- Section 4.4, `--ast`, AST printed as an indented tree and as JSON.
- Example grammars that must build conflict-free and parse sample inputs in `examples/inputs/`:
  - `calc.lg`
  - `json.lg` (full JSON: objects, arrays, strings with escapes, numbers, true, false, null)
  - `minic.lg` (a C subset: `int` declarations, assignments, `if/else`, `while`, `return`, function definitions and calls, expressions with full C precedence for `|| && == != < <= > >= + - * / % !` and unary minus)

### M8: Polish and stretch goals
- Panic-mode error recovery (section 7).
- Optional: DeRemer and Pennello LALR, cross-checked against the merge method.
- Optional: Bison cross-check script `scripts/bison_compare.sh` that converts an `.lg` grammar to Bison syntax and compares conflict counts (only runs if `bison` is on PATH; never required for `mvn test`).
- Optional: generate a standalone Java parser class from the JSON tables (code generation).
- README with build steps, CLI examples, rendered DOT screenshots and the `compare` output for each example.

## 11. docs/ALGORITHMS.md (required)

Write this for a student preparing for a viva, not for an expert. For each algorithm below, include: what problem it solves, the algorithm in plain steps, a small worked example traced by hand, the class and method names in this codebase that implement it, and complexity notes.

1. Regex parsing and desugaring
2. Alphabet compression into character classes
3. Thompson construction
4. ε-closure and subset construction
5. Hopcroft minimization, and why the initial partition is per token type
6. Maximal munch and priority
7. Nullable, FIRST, FOLLOW by fixed point
8. LR(0) items, closure, goto, canonical collection, and why it is itself a DFA (of viable prefixes)
9. SLR table construction and its weakness
10. LR(1) items and lookahead computation via FIRST(βa)
11. LALR merging, and why merging can add reduce/reduce but never shift/reduce conflicts
12. Precedence and associativity resolution
13. Shortest viable-prefix counterexamples
14. The shift-reduce driver and panic-mode recovery

Also include a short section "Common viva questions" with answers, for example: difference between NFA and DFA, why subset construction can blow up exponentially, why LALR is used by Yacc, what a viable prefix is, why the expression grammar is SLR but `S -> L = R` is not.

## 12. Code quality rules

- Each algorithm lives in its own class with a Javadoc comment naming the algorithm and its textbook source (for example, "Aho, Lam, Sethi, Ullman, Compilers, section 4.7.4").
- No method over about 60 lines; prefer small named helpers that mirror the textbook steps.
- Immutable data where practical (records, unmodifiable collections). Item sets must have correct `equals` and `hashCode` so deduplication is reliable.
- No `System.exit` outside `cli.Main`.
- All user-facing errors carry source positions.

## 13. Out of scope

- Regex features that are not regular (backreferences, lookaround).
- GLR, LL(*), PEG or Earley parsing.
- Incremental or streaming parsing.
- IDE plugins or a GUI. (A web visualizer could be a later project that reads the JSON output.)

## 14. Definition of done

- `mvn package` produces `target/lalrgen.jar`; `java -jar target/lalrgen.jar --help` works.
- `mvn test` passes with all M1 to M7 tests.
- `lalrgen compare` on `examples/*.lg` prints the expected state and conflict counts from section 10.
- `docs/ALGORITHMS.md` covers every item in section 11.
- README shows a full walkthrough: write a spec, check it, view the automata, parse an input and print the AST.
