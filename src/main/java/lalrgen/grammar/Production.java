package lalrgen.grammar;

import lalrgen.spec.Assoc;
import lalrgen.spec.AstExpr;
import lalrgen.spec.Pos;

import java.util.Arrays;

/**
 * A production lhs -> rhs[0] rhs[1] ... Production 0 is always the augmented S' -> S.
 *
 * @param precLevel precedence level used for shift/reduce resolution (0 = none)
 * @param assoc     associativity belonging to precLevel (null if none)
 * @param ast       AST annotation, or null
 */
public record Production(int id, int lhs, int[] rhs, int precLevel, Assoc assoc, AstExpr ast, Pos pos) {

    public int length() {
        return rhs.length;
    }

    /** "E -> E '+' T" using the grammar's symbol names. */
    public String show(Grammar g) {
        StringBuilder sb = new StringBuilder(g.name(lhs)).append(" ->");
        if (rhs.length == 0) sb.append(" %empty");
        for (int s : rhs) sb.append(' ').append(g.name(s));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Production p && p.id == id && p.lhs == lhs && Arrays.equals(p.rhs, rhs);
    }

    @Override
    public int hashCode() {
        return 31 * id + Arrays.hashCode(rhs);
    }

    @Override
    public String toString() {
        return "Production" + id + Arrays.toString(rhs);
    }
}
