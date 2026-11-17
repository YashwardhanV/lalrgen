package lalrgen.spec;

import java.util.List;

/**
 * One alternative of a rule: a sequence of symbols (possibly empty), an optional %prec symbol and
 * an optional AST annotation.
 */
public record Alt(List<SymRef> symbols, SymRef prec, AstExpr ast, Pos pos) {}
