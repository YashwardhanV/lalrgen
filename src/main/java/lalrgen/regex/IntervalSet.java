package lalrgen.regex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable set of Unicode code points stored as sorted, disjoint, non-adjacent inclusive
 * ranges [lo, hi]. This is how character classes like [a-z0-9_] and [^\n] are represented,
 * so a class never has to be expanded into thousands of single characters.
 */
public final class IntervalSet {
    public static final int MAX_CODE_POINT = Character.MAX_CODE_POINT; // 0x10FFFF
    public static final IntervalSet EMPTY = new IntervalSet(new int[0]);
    public static final IntervalSet ALL = range(0, MAX_CODE_POINT);

    /** Flattened pairs: lo0, hi0, lo1, hi1, ... sorted and merged. */
    private final int[] ranges;

    private IntervalSet(int[] ranges) {
        this.ranges = ranges;
    }

    public static IntervalSet of(int codePoint) {
        return new IntervalSet(new int[] {codePoint, codePoint});
    }

    public static IntervalSet range(int lo, int hi) {
        if (lo > hi) throw new IllegalArgumentException("bad range " + lo + "-" + hi);
        return new IntervalSet(new int[] {lo, hi});
    }

    /** Builds a set from arbitrary (possibly overlapping, unsorted) pairs. */
    public static IntervalSet fromPairs(List<int[]> pairs) {
        List<int[]> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] p : sorted) {
            if (!merged.isEmpty() && p[0] <= merged.get(merged.size() - 1)[1] + 1) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], p[1]);
            } else {
                merged.add(new int[] {p[0], p[1]});
            }
        }
        int[] r = new int[merged.size() * 2];
        for (int k = 0; k < merged.size(); k++) {
            r[2 * k] = merged.get(k)[0];
            r[2 * k + 1] = merged.get(k)[1];
        }
        return new IntervalSet(r);
    }

    public IntervalSet union(IntervalSet other) {
        List<int[]> pairs = new ArrayList<>(this.pairs());
        pairs.addAll(other.pairs());
        return fromPairs(pairs);
    }

    /** Complement relative to all code points 0..0x10FFFF. */
    public IntervalSet complement() {
        List<int[]> out = new ArrayList<>();
        int next = 0;
        for (int k = 0; k < ranges.length; k += 2) {
            if (ranges[k] > next) out.add(new int[] {next, ranges[k] - 1});
            next = ranges[k + 1] + 1;
        }
        if (next <= MAX_CODE_POINT) out.add(new int[] {next, MAX_CODE_POINT});
        return fromPairs(out);
    }

    public boolean contains(int cp) {
        // binary search over range starts
        int lo = 0;
        int hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < ranges[2 * mid]) hi = mid - 1;
            else if (cp > ranges[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return ranges.length == 0;
    }

    /** The ranges as a list of {lo, hi} arrays (fresh copies). */
    public List<int[]> pairs() {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < ranges.length; k += 2) out.add(new int[] {ranges[k], ranges[k + 1]});
        return out;
    }

    public int rangeCount() {
        return ranges.length / 2;
    }

    public int lo(int k) {
        return ranges[2 * k];
    }

    public int hi(int k) {
        return ranges[2 * k + 1];
    }

    /** Number of code points in the set (as a long, since ALL has 1.1 million). */
    public long size() {
        long n = 0;
        for (int k = 0; k < ranges.length; k += 2) n += ranges[k + 1] - ranges[k] + 1L;
        return n;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IntervalSet s && Arrays.equals(ranges, s.ranges);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ranges);
    }

    /**
     * Compact human-readable form used in DOT labels and tables: a, a-z, [^\n], any.
     * Large sets are shown as their complement when that is shorter.
     */
    @Override
    public String toString() {
        if (isEmpty()) return "[]";
        if (this.equals(ALL)) return "any";
        IntervalSet comp = complement();
        if (comp.rangeCount() < rangeCount() && size() > 0x8000) {
            return "[^" + comp.body() + "]";
        }
        if (rangeCount() == 1 && lo(0) == hi(0)) return show(lo(0));
        return "[" + body() + "]";
    }

    private String body() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < ranges.length; k += 2) {
            int lo = ranges[k];
            int hi = ranges[k + 1];
            sb.append(show(lo));
            if (hi == lo + 1) sb.append(show(hi));
            else if (hi > lo) sb.append('-').append(show(hi));
        }
        return sb.toString();
    }

    /** Printable form of one code point, escaping specials. */
    public static String show(int cp) {
        return switch (cp) {
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            case ' ' -> "' '";
            case '\\' -> "\\\\";
            case '-', '[', ']', '^' -> "\\" + (char) cp;
            default -> {
                if (cp < 0x20 || cp == 0x7F) yield String.format("\\x%02X", cp);
                boolean printable = cp < 0x80
                        || (Character.isDefined(cp) && !Character.isISOControl(cp)
                            && !(cp >= 0xD800 && cp <= 0xDFFF) && !Character.isWhitespace(cp)
                            && Character.getType(cp) != Character.PRIVATE_USE);
                if (!printable) yield String.format("\\u{%X}", cp);
                yield new String(Character.toChars(cp));
            }
        };
    }
}
