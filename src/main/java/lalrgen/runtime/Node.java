package lalrgen.runtime;

import lalrgen.lexer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * A parse-tree or AST node. Leaves come from tokens and carry text; inner nodes carry a label
 * (the non-terminal name in a parse tree, or the constructor name from an AST annotation).
 */
public record Node(String label, String text, List<Node> children, int line, int col) {

    public static Node leaf(Token t) {
        return new Node(t.kind(), t.text(), List.of(), t.line(), t.col());
    }

    public static Node inner(String label, List<Node> children) {
        int line = 0;
        int col = 0;
        for (Node c : children) {
            if (c.line() > 0) {
                line = c.line();
                col = c.col();
                break;
            }
        }
        return new Node(label, null, List.copyOf(children), line, col);
    }

    public boolean isLeaf() {
        return text != null;
    }

    /** Indented tree, one node per line. */
    public String pretty() {
        StringBuilder sb = new StringBuilder();
        pretty(sb, "");
        return sb.toString();
    }

    private void pretty(StringBuilder sb, String indent) {
        // a node whose only child is a token prints on one line: Num: NUMBER "42"
        if (!isLeaf() && children.size() == 1 && children.get(0).isLeaf()) {
            sb.append(indent).append(label).append(": ").append(children.get(0).headline()).append('\n');
            return;
        }
        sb.append(indent).append(headline()).append('\n');
        for (Node c : children) c.pretty(sb, indent + "  ");
    }

    private String headline() {
        if (!isLeaf()) return label;
        if (label.startsWith("'")) return label;
        return label + " " + Token.quote(text);
    }

    /** Compact single-line form, e.g. Add(Num(1), Num(2)). Leaves print as their text. */
    public String sexpr() {
        if (isLeaf()) return text;
        StringBuilder sb = new StringBuilder(label).append('(');
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(children.get(i).sexpr());
        }
        return sb.append(')').toString();
    }

    /** JSON form: {"label": ..., "children": [...]} or {"token": ..., "text": ...}. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        json(sb, "");
        return sb.toString();
    }

    private void json(StringBuilder sb, String indent) {
        if (isLeaf()) {
            sb.append("{\"token\": ").append(lalrgen.viz.Json.str(label)).append(", \"text\": ")
                    .append(lalrgen.viz.Json.str(text)).append(", \"line\": ").append(line)
                    .append(", \"col\": ").append(col).append('}');
            return;
        }
        sb.append("{\"label\": ").append(lalrgen.viz.Json.str(label)).append(", \"children\": [");
        if (children.isEmpty()) {
            sb.append("]}");
            return;
        }
        String inner = indent + "  ";
        for (int i = 0; i < children.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n").append(inner);
            children.get(i).json(sb, inner);
        }
        sb.append('\n').append(indent).append("]}");
    }

    /** All leaves, left to right (the "yield" of the tree). */
    public List<Node> leaves() {
        List<Node> out = new ArrayList<>();
        collect(out);
        return out;
    }

    private void collect(List<Node> out) {
        if (isLeaf()) out.add(this);
        for (Node c : children) c.collect(out);
    }
}
