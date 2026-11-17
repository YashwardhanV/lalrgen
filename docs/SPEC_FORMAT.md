# The .lg spec format

A spec file has a **declarations** section, a `%%` line, then a **rules** section.
Comments start with `#` and run to the end of the line (except inside a regex or a quoted literal).

```
# calc.lg
%token NUM   /[0-9]+(\.[0-9]+)?/
%token ID    /[a-zA-Z_][a-zA-Z0-9_]*/
%skip        /[ \t\r\n]+/

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

## Declarations

| Directive | Meaning |
|---|---|
| `%token NAME /regex/` | A token type defined by a regex. Write a literal `/` inside the regex as `\/`. |
| `%skip /regex/` | Text matched here is thrown away (whitespace, comments). |
| `%left A B ...` | Left-associative tokens. Every later precedence line binds **tighter**. |
| `%right A B ...` | Right-associative tokens. |
| `%nonassoc A B ...` | Non-associative: `a < b < c` becomes a syntax error. |
| `%start NAME` | The start symbol. Default: the left side of the first rule. |
| `%sync A B ...` | Synchronizing tokens for error recovery. Default: `';'` and `'}'` if the grammar uses them. |

Precedence directives may list `%token` names, quoted literals, or **pseudo-tokens** such as
`UMINUS` that exist only to be named in `%prec`.

## Rules

```
rule : NAME ':' alt ('|' alt)* ';'
alt  : symbol* ('%prec' NAME)? ('->' ast)?
```

* A `NAME` declared with `%token` is a terminal; any other `NAME` must be defined by a rule.
* A quoted literal such as `'+'`, `'=='` or `'while'` defines an exact-match token automatically.
  Literals have higher priority than every `%token` regex, which is how keywords beat `ID`.
  Escapes inside literals: `\'` `\\` `\n` `\t` `\r`. Unicode is fine (`'अगर'`).
* An empty alternative, or the keyword `%empty`, derives the empty string.
* A rule name may have several `rule : ... ;` blocks; their alternatives are combined.

### How the lexer picks a token

1. **Longest match** (maximal munch): `>=` is one token, not `>` then `=`.
2. On a tie in length, the rule with higher **priority** wins: literals first (in order of first
   appearance in the rules), then `%token` and `%skip` in file order.
3. LALRGen warns when a `%token` can never win (every string it matches is claimed by a rule with
   higher priority).

## Regex syntax

| Syntax | Meaning |
|---|---|
| `a` | the character a |
| `\n \t \r \f \v \0` | control characters |
| `\x41`, `é`, `\u{1F600}` | characters by code point |
| `\. \* \+ \? \| \( \) \[ \] \{ \} \\ \/ \- \^` | escaped specials (any non-alphanumeric character can be escaped) |
| `.` | any character except newline |
| `[abc]`, `[a-z0-9_]`, `[^"\\]` | character class, negated class |
| `\d \w \s` and `\D \W \S` | digit, word character, whitespace (also inside classes) |
| `AB` | concatenation |
| `A\|B` | alternation |
| `A*`, `A+`, `A?` | zero or more, one or more, optional |
| `A{m}`, `A{m,}`, `A{m,n}` | bounded repetition (counts up to 1000) |
| `(A)` | grouping |

Precedence, highest first: postfix operators, then concatenation, then `|`.
Inside a class, `]` right after `[` or `[^` is a literal, and `-` first or last is a literal.
Not supported, because they are not regular or not needed: backreferences, lookaround, anchors.

A `%token` or `%skip` regex that can match the empty string is an error (the lexer would loop).

## AST annotations

```
ast  : '$' N               # pass child N through unchanged (1-based)
     | NAME                # a node with no children, e.g. True
     | NAME '(' ast, ... ')'
```

`List(...)` is special: any argument that is itself a `List` node is spliced in. That turns the
usual left-recursive list rule into a flat list:

```
stmts : stmt          -> List($1)
      | stmts stmt    -> List($1, $2)
      ;
```

gives `List(s1, s2, s3)` and not `List(List(List(s1), s2), s3)`.

An alternative without `->` produces a generic node labelled with the non-terminal's name and all
of its children. Run `parse` without `--ast` to see the full parse tree instead.

## How conflicts are resolved (same rules as Yacc and Bison)

* **reduce/reduce**: the production written first in the file wins. Reported as a warning.
* **shift/reduce** where both the lookahead token and the production have a precedence:
  * higher precedence wins;
  * equal precedence: `%left` reduces, `%right` shifts, `%nonassoc` makes the cell an error.
  * These are resolved silently (see them with `check --verbose`).
* **shift/reduce** otherwise: shift. Reported as a warning.

A production's precedence is its `%prec` symbol's, or else that of its rightmost terminal that
has one.

## Errors and warnings

Every problem is reported as `file:line:col: error: message`. Errors include: undefined symbols,
duplicate tokens, invalid regexes (with the column of the bad character), regexes that match the
empty string, non-terminals that cannot derive any token string, `%prec` names without a
precedence, `$n` out of range. Warnings include: unreachable non-terminals, unused tokens,
tokens that can never be matched, and unresolved grammar conflicts.
