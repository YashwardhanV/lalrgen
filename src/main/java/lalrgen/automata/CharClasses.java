package lalrgen.automata;

import lalrgen.regex.IntervalSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Alphabet compression. Given every character set used by the regexes, splits the code point
 * space into the coarsest partition of disjoint "character classes" such that each input set is
 * an exact union of classes.
 *
 * <p>Example: sets {@code [a-z]} and {@code [0-9a-f]} give classes {@code [a-f]}, {@code [g-z]},
 * {@code [0-9]}. The DFA then needs 3 columns instead of one per character, and {@code [^\n]}
 * (a million code points) is just one or two classes.
 *
 * <p>Method: cut the number line at every range boundary to get elementary intervals, then group
 * intervals that belong to exactly the same input sets (same "signature").
 */
public final class CharClasses {
    /** starts[e] is the first code point of elementary interval e; it ends at starts[e+1]-1. */
    private final int[] starts;
    /** classOf[e] = class id of elementary interval e, or -1 if no regex uses it. */
    private final int[] classOf;
    private final List<IntervalSet> members;
    private final Map<IntervalSet, BitSet> classesOfSet = new HashMap<>();

    private CharClasses(int[] starts, int[] classOf, List<IntervalSet> members) {
        this.starts = starts;
        this.classOf = classOf;
        this.members = members;
    }

    public static CharClasses build(Collection<IntervalSet> inputSets) {
        List<IntervalSet> sets = new ArrayList<>(new java.util.LinkedHashSet<>(inputSets));
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(0);
        for (IntervalSet s : sets) {
            for (int k = 0; k < s.rangeCount(); k++) {
                cuts.add(s.lo(k));
                if (s.hi(k) < IntervalSet.MAX_CODE_POINT) cuts.add(s.hi(k) + 1);
            }
        }
        int[] starts = cuts.stream().mapToInt(Integer::intValue).toArray();
        int[] classOf = new int[starts.length];
        Map<BitSet, Integer> idOfSignature = new LinkedHashMap<>();
        List<List<int[]>> memberPairs = new ArrayList<>();
        BitSet[] signatureOfClass = new BitSet[starts.length];
        for (int e = 0; e < starts.length; e++) {
            BitSet sig = new BitSet();
            for (int s = 0; s < sets.size(); s++) {
                if (sets.get(s).contains(starts[e])) sig.set(s);
            }
            if (sig.isEmpty()) {
                classOf[e] = -1;
                continue;
            }
            Integer id = idOfSignature.get(sig);
            if (id == null) {
                id = idOfSignature.size();
                idOfSignature.put(sig, id);
                memberPairs.add(new ArrayList<>());
                signatureOfClass[id] = sig;
            }
            classOf[e] = id;
            int hi = e + 1 < starts.length ? starts[e + 1] - 1 : IntervalSet.MAX_CODE_POINT;
            memberPairs.get(id).add(new int[] {starts[e], hi});
        }
        List<IntervalSet> members = new ArrayList<>();
        for (List<int[]> p : memberPairs) members.add(IntervalSet.fromPairs(p));
        CharClasses cc = new CharClasses(starts, classOf, List.copyOf(members));
        for (int s = 0; s < sets.size(); s++) {
            BitSet classes = new BitSet();
            for (int c = 0; c < members.size(); c++) {
                if (signatureOfClass[c].get(s)) classes.set(c);
            }
            cc.classesOfSet.put(sets.get(s), classes);
        }
        return cc;
    }

    /** Class id of a code point, or -1 if no regex mentions it (always a dead transition). */
    public int classify(int cp) {
        int idx = Arrays.binarySearch(starts, cp);
        if (idx < 0) idx = -idx - 2;
        return classOf[idx];
    }

    public int count() {
        return members.size();
    }

    /** The code points belonging to a class. */
    public IntervalSet members(int classId) {
        return members.get(classId);
    }

    /** The classes whose union is the given set (the set must be one of the inputs). */
    public BitSet classesOf(IntervalSet set) {
        BitSet b = classesOfSet.get(set);
        if (b == null) throw new IllegalArgumentException("set was not part of the alphabet: " + set);
        return b;
    }

    /** Elementary interval boundaries and their classes, for JSON export: {lo, hi, class}. */
    public List<int[]> intervalTable() {
        List<int[]> out = new ArrayList<>();
        for (int e = 0; e < starts.length; e++) {
            if (classOf[e] < 0) continue;
            int hi = e + 1 < starts.length ? starts[e + 1] - 1 : IntervalSet.MAX_CODE_POINT;
            out.add(new int[] {starts[e], hi, classOf[e]});
        }
        return out;
    }
}
