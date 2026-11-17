#!/usr/bin/env bash
# Cross-checks LALRGen against GNU Bison on every example grammar.
# For each .lg file: export it to Bison syntax, run Bison, and compare
#   - the number of LALR states (Bison has exactly one extra state, for shifting $end)
#   - the number of unresolved shift/reduce and reduce/reduce conflicts.
# Needs: bison on PATH, and target/lalrgen.jar (run "mvn package" first).
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=target/lalrgen.jar
command -v bison >/dev/null || { echo "bison not found on PATH; skipping"; exit 0; }
[ -f "$JAR" ] || { echo "missing $JAR: run 'mvn package' first"; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
status=0
printf "%-20s %14s %14s %10s %10s  %s\n" grammar "states(ours)" "states(bison)" "s/r o|b" "r/r o|b" result
for spec in examples/*.lg; do
  name=$(basename "$spec" .lg)
  java -jar "$JAR" bison "$spec" -o "$tmp/$name.y" 2>/dev/null
  read -r ours_states ours_sr ours_rr < <(java -jar "$JAR" compare "$spec" 2>/dev/null | awk '$1=="lalr"{print $2, $3, $4}')
  msgs=$(cd "$tmp" && bison -Wno-other --report=state -o "$name.tab.c" "$name.y" 2>&1 || true)
  b_states=$(grep -cE '^State [0-9]+$' "$tmp/$name.output")
  b_sr=$(echo "$msgs" | grep -oE '[0-9]+ shift/reduce' | grep -oE '^[0-9]+' || echo 0)
  b_rr=$(echo "$msgs" | grep -oE '[0-9]+ reduce/reduce' | grep -oE '^[0-9]+' || echo 0)
  if [ "$b_states" -eq $((ours_states + 1)) ] && [ "$b_sr" -eq "$ours_sr" ] && [ "$b_rr" -eq "$ours_rr" ]; then
    result=OK
  else
    result=MISMATCH; status=1
  fi
  printf "%-20s %14s %14s %10s %10s  %s\n" "$name" "$ours_states" "$b_states" "$ours_sr|$b_sr" "$ours_rr|$b_rr" "$result"
done
exit $status
