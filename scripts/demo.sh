#!/usr/bin/env bash
# A guided tour of LALRGen for a demo or viva. Press Enter between steps.
# Needs target/lalrgen.jar (run "mvn package" first). Graphviz "dot" is optional.
set -uo pipefail
cd "$(dirname "$0")/.."
L="java -jar target/lalrgen.jar"
step() { echo; echo "================ $1"; echo "\$ ${*:2}"; "${@:2}"; read -r -p "[Enter] " _ || true; }

step "1. Lexer pipeline sizes, and the parse table summary" $L check examples/calc.lg
step "2. Tokens with line:column (maximal munch, keywords beat ID)" $L lex examples/calc.lg examples/inputs/calc_ok.txt
step "3. FIRST and FOLLOW sets" $L sets examples/expr.lg
step "4. LR(0) item sets of the expression grammar (12 states)" $L states examples/expr.lg --mode slr
step "5. The SLR parse table" $L tables examples/expr.lg --mode slr
step "6. Every shift and reduce for id + id * id" $L parse examples/expr.lg examples/inputs/expr.txt --trace --mode slr
step "7. SLR fails where LR(1) and LALR succeed" $L compare examples/not_slr.lg
step "8. ...and why SLR fails" $L check examples/not_slr.lg --mode slr
step "9. LR(1) has 10 states, LALR merges them into 7" $L compare examples/cc.lg
step "10. LALR merging can create a reduce/reduce conflict" $L check examples/not_lalr.lg --expand
step "11. Dangling else: one shift/reduce conflict, default shift" $L check examples/dangling_else.lg --expand
step "12. Precedence decides associativity: 1-2-3 and 2^3^2" $L parse examples/calc.lg examples/inputs/calc_ok.txt --ast
step "13. A C subset: AST of a real program" $L parse examples/minic.lg examples/inputs/minic_ok.txt --ast
step "14. Error messages and panic-mode recovery" $L parse examples/minic.lg examples/inputs/minic_bad.txt
if command -v dot >/dev/null; then
  mkdir -p out
  for w in nfa dfa mindfa; do $L dot docs/images/abb.lg --what $w -o out/abb_$w.dot && dot -Tsvg out/abb_$w.dot -o out/abb_$w.svg; done
  for w in lr1 lalr; do $L dot examples/cc.lg --what $w -o out/cc_$w.dot && dot -Tsvg out/cc_$w.dot -o out/cc_$w.svg; done
  echo; echo "SVG diagrams written to out/"
fi
