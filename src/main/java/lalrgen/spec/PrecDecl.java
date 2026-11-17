package lalrgen.spec;

import java.util.List;

/**
 * A %left / %right / %nonassoc line. Names are token names, quoted literals (kept with their
 * quotes, e.g. "'+'") or pseudo-tokens used only in %prec.
 *
 * @param level precedence level; later lines get higher levels (1, 2, 3, ...)
 */
public record PrecDecl(Assoc assoc, List<SymRef> names, int level, Pos pos) {}
