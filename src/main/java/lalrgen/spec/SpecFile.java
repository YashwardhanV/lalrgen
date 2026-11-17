package lalrgen.spec;

import java.util.List;

/**
 * The parsed contents of a .lg spec file, before any semantic checks.
 *
 * @param fileName  name used in diagnostics
 * @param tokens    %token and %skip rules in file order
 * @param precs     precedence declarations in file order (level 1 = lowest)
 * @param start     the %start symbol, or null if not given
 * @param sync      %sync tokens for panic-mode error recovery (empty = use defaults)
 * @param rules     grammar rules in file order
 * @param warnings  non-fatal diagnostics found while parsing
 */
public record SpecFile(
        String fileName,
        List<TokenDef> tokens,
        List<PrecDecl> precs,
        SymRef start,
        List<SymRef> sync,
        List<RuleDef> rules,
        List<Diagnostic> warnings) {}
