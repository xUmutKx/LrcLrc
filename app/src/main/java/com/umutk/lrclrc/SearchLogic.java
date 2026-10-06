package com.umutk.lrclrc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Android-independent search rules (unit-testable with plain javac).
 *
 *  - Plain text            : a multi-word query is ONE phrase; the words must follow each other.
 *  - Comma separated parts : every part must occur somewhere in the song (AND), not necessarily
 *                            on the same line. Each part itself is a phrase.
 *  - Words of 1-2 letters never count: they are removed from the query and from the lyrics, so a phrase
 *    is matched on its main words only; a part made only of short words is ignored.
 *  - Matching runs on normalized text: Turkish lower case, anything that is not a letter or
 *    digit becomes a single space. Phrases may span line breaks.
 */
public final class SearchLogic {
    private SearchLogic() {}

    public static final int MIN_WORD_LEN = 3;

    /** Turkish-aware lowercase for one char (length preserving): I->ı, İ->i. */
    public static char trLowerChar(char c) {
        if (c == 'I') return 'ı';
        if (c == 'İ') return 'i';
        return Character.toLowerCase(c);
    }

    public static String trLower(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'I') sb.append('ı');
            else if (c == 'İ') sb.append('i');
            else sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Normalized string plus, for each normalized char, the index of its source char in the raw text. */
    public static final class Norm {
        public final String text;
        public final int[] rawIdx;
        Norm(String t, int[] m) { text = t; rawIdx = m; }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.isSurrogate(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    /** Words shorter than MIN_WORD_LEN are dropped (in the query and in the lyrics), so they never influence a result. */
    public static Norm normalize(String raw, boolean ci) {
        StringBuilder sb = new StringBuilder(raw.length());
        int[] map = new int[raw.length() + 1];
        int i = 0, n = raw.length();
        while (i < n) {
            if (!isWordChar(raw.charAt(i))) { i++; continue; }
            int j = i;
            while (j < n && isWordChar(raw.charAt(j))) j++;
            if (j - i >= MIN_WORD_LEN) {
                if (sb.length() > 0) { map[sb.length()] = i - 1 < 0 ? 0 : i - 1; sb.append(' '); }
                for (int k = i; k < j; k++) { char c = raw.charAt(k); map[sb.length()] = k; sb.append(ci ? trLowerChar(c) : c); }
            }
            i = j;
        }
        int[] m = new int[sb.length()];
        System.arraycopy(map, 0, m, 0, sb.length());
        return new Norm(sb.toString(), m);
    }

    // ── Query parsing ────────────────────────────────────────────────────────

    public static final class Query {
        /** Normalized phrases, all of which must occur in a song. */
        public final List<String> parts = new ArrayList<>();
        /** Original (trimmed) parts that were dropped because they had no word of 3+ letters. */
        public final List<String> ignored = new ArrayList<>();
        public boolean isEmpty() { return parts.isEmpty(); }
    }

    public static Query parse(String q, boolean ci) {
        Query out = new Query();
        if (q == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : q.split("[,،;]")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) continue;
            String norm = normalize(trimmed, ci).text;
            if (norm.isEmpty()) { out.ignored.add(trimmed); continue; }
            if (seen.add(norm)) out.parts.add(norm);
        }
        return out;
    }

    // ── Per-song index ───────────────────────────────────────────────────────

    /**
     * Normalized lyrics of one song: non-empty normalized lines joined by single spaces.
     * segLine[k] is the original line index of segment k, segStart/segEnd its range in text.
     */
    public static final class Index {
        public final String text;
        public final int[] segLine, segStart, segEnd;
        Index(String t, int[] l, int[] s, int[] e) { text = t; segLine = l; segStart = s; segEnd = e; }
    }

    public static Index buildIndex(List<String> lines, boolean ci) {
        StringBuilder sb = new StringBuilder();
        int n = lines.size();
        int[] sl = new int[n], ss = new int[n], se = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            String t = normalize(lines.get(i), ci).text;
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sl[k] = i; ss[k] = sb.length();
            sb.append(t);
            se[k] = sb.length();
            k++;
        }
        return new Index(sb.toString(),
                java.util.Arrays.copyOf(sl, k), java.util.Arrays.copyOf(ss, k), java.util.Arrays.copyOf(se, k));
    }

    /** All occurrences of a normalized phrase as {start,end} in idx.text. */
    public static List<int[]> find(Index idx, String phrase, boolean wholeWord) {
        List<int[]> res = new ArrayList<>();
        if (phrase.isEmpty()) return res;
        String hay = idx.text;
        int from = 0;
        while (true) {
            int p = hay.indexOf(phrase, from);
            if (p < 0) break;
            int e = p + phrase.length();
            boolean ok = !wholeWord || ((p == 0 || hay.charAt(p - 1) == ' ') && (e >= hay.length() || hay.charAt(e) == ' '));
            if (ok) res.add(new int[]{p, e});
            from = p + 1;
        }
        return res;
    }

    public static boolean contains(Index idx, String phrase, boolean wholeWord) {
        if (!wholeWord) return !phrase.isEmpty() && idx.text.contains(phrase);
        return !find(idx, phrase, true).isEmpty();
    }

    /** Segments touched by [s,e): returns triples {segmentIndex, localStart, localEnd} (offsets in the normalized line). */
    public static List<int[]> segmentsFor(Index idx, int s, int e) {
        List<int[]> r = new ArrayList<>();
        // binary search first segment with segEnd > s
        int lo = 0, hi = idx.segLine.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (idx.segEnd[mid] > s) hi = mid; else lo = mid + 1; }
        for (int k = lo; k < idx.segLine.length && idx.segStart[k] < e; k++) {
            int a = Math.max(s, idx.segStart[k]) - idx.segStart[k];
            int b = Math.min(e, idx.segEnd[k]) - idx.segStart[k];
            if (b > a) r.add(new int[]{k, a, b});
        }
        return r;
    }

    /** Maps a normalized range [a,b) of a raw line back to a raw range {start,len}; null if impossible. */
    public static int[] rawRange(String rawLine, boolean ci, int a, int b) {
        Norm n = normalize(rawLine, ci);
        if (a < 0 || b > n.text.length() || a >= b) return null;
        int rs = n.rawIdx[a];
        int re = n.rawIdx[b - 1] + 1;
        return new int[]{rs, re - rs};
    }
}
