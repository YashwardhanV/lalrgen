package lalrgen.spec;

import java.util.List;

/**
 * An AST annotation written after "->" in a rule alternative.
 * <ul>
 *   <li>{@code $2} : pass child 2 through unchanged ({@link ChildRef})</li>
 *   <li>{@code Add($1, $3)} : build a node labelled Add ({@link Ctor})</li>
 *   <li>{@code List(...)} : like Ctor, but children that are themselves List nodes are spliced in,
 *       which flattens left-recursive list rules</li>
 * </ul>
 */
public sealed interface AstExpr permits AstExpr.ChildRef, AstExpr.Ctor {

    Pos pos();

    record ChildRef(int index, Pos pos) implements AstExpr {
        @Override
        public String toString() {
            return "$" + index;
        }
    }

    record Ctor(String name, List<AstExpr> args, Pos pos) implements AstExpr {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i));
            }
            return sb.append(')').toString();
        }
    }
}
