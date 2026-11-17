package lalrgen.spec;

import java.util.List;

/** A rule "lhs : alt | alt ... ;". */
public record RuleDef(String lhs, List<Alt> alts, Pos pos) {}
