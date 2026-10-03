import com.umutk.lrclrc.SearchLogic;
import java.util.*;

public class SearchLogicTest {
    static int fail = 0;
    static void check(boolean c, String m) { if (!c) { fail++; System.out.println("FAIL: " + m); } else System.out.println("ok: " + m); }
    static boolean match(List<String> lines, String q, boolean ci, boolean ww) {
        SearchLogic.Query pq = SearchLogic.parse(q, ci);
        if (pq.isEmpty()) return false;
        SearchLogic.Index idx = SearchLogic.buildIndex(lines, ci);
        for (String p : pq.parts) if (!SearchLogic.contains(idx, p, ww)) return false;
        return true;
    }
    public static void main(String[] a) {
        List<String> song = Arrays.asList("Seni seviyorum, gerçekten!", "Yarın yağmur yağacak...", "Aşk  bitti", "ve  sen", "  gittin  ");
        check(SearchLogic.trLower("IĞDIR İSTANBUL").equals("ığdır istanbul"), "trLower");
        check(match(song, "seni seviyorum", true, false), "phrase same line w/ punctuation");
        check(match(song, "SENİ   Seviyorum", true, false), "case + multi space");
        check(!match(song, "seviyorum seni", true, false), "phrase order matters");
        check(match(song, "bitti ve sen", true, false), "phrase across lines");
        check(match(song, "yağacak aşk", true, false), "across line break w/ ellipsis");
        check(!match(song, "seni yağmur", true, false), "non-adjacent words fail as phrase");
        check(match(song, "aşk, yağmur", true, false), "comma AND different lines");
        check(!match(song, "aşk, kedi", true, false), "comma AND missing part");
        check(match(song, "seni seviyorum, yarın", true, false), "phrase + word");
        check(!match(song, "ab", true, false), "short ignored -> empty");
        SearchLogic.Query q = SearchLogic.parse("aşk, ab, yağmur", true);
        check(q.parts.size() == 2 && q.ignored.size() == 1 && q.ignored.get(0).equals("ab"), "short part ignored in list");
        check(match(song, "aşk, ab", true, false), "remaining part still searched");
        check(match(song, "ve sen", true, false), "short words inside phrase kept");
        check(match(song, "\"seni seviyorum\"", true, false), "quotes harmless");
        check(match(song, "sev", true, false) && !match(song, "sev", true, true), "ww: substring vs whole word");
        check(match(song, "aşk bitti", true, true), "ww phrase");
        check(!match(song, "seni seviyor", true, true), "ww phrase prefix fails");
        check(match(song, "Seni seviyorum", false, false) && !match(song, "seni seviyorum", false, false), "case-sensitive");
        // highlight mapping
        SearchLogic.Index idx = SearchLogic.buildIndex(song, true);
        List<int[]> occ = SearchLogic.find(idx, "seviyorum gerçekten", false);
        check(occ.size() == 1, "found one occurrence");
        List<int[]> segs = SearchLogic.segmentsFor(idx, occ.get(0)[0], occ.get(0)[1]);
        int[] rr = SearchLogic.rawRange(song.get(0), true, segs.get(0)[1], segs.get(0)[2]);
        check(song.get(0).substring(rr[0], rr[0] + rr[1]).equals("seviyorum, gerçekten"), "raw highlight spans punctuation: " + song.get(0).substring(rr[0], rr[0] + rr[1]));
        occ = SearchLogic.find(idx, "bitti ve sen", false);
        segs = SearchLogic.segmentsFor(idx, occ.get(0)[0], occ.get(0)[1]);
        check(segs.size() == 2 && segs.get(0)[0] + 1 == segs.get(1)[0], "cross-line gives 2 segments");
        int[] r1 = SearchLogic.rawRange(song.get(2), true, segs.get(0)[1], segs.get(0)[2]);
        int[] r2 = SearchLogic.rawRange(song.get(3), true, segs.get(1)[1], segs.get(1)[2]);
        check(song.get(2).substring(r1[0], r1[0] + r1[1]).equals("bitti") && song.get(3).substring(r2[0], r2[0] + r2[1]).equals("ve  sen"), "cross-line raw ranges");
        // perf
        List<List<String>> songs = new ArrayList<>();
        Random rnd = new Random(1);
        for (int i = 0; i < 5000; i++) { List<String> l = new ArrayList<>(); for (int j = 0; j < 40; j++) l.add("kelime" + rnd.nextInt(5000) + " aşk yağmur, test" + rnd.nextInt(100)); songs.add(l); }
        List<SearchLogic.Index> ix = new ArrayList<>();
        long t = System.nanoTime(); for (List<String> l : songs) ix.add(SearchLogic.buildIndex(l, true));
        long t1 = System.nanoTime(); int c = 0;
        for (SearchLogic.Index i : ix) if (SearchLogic.contains(i, "aşk yağmur test7", false)) c++;
        System.out.println("index build ms=" + (t1 - t) / 1000000 + " search ms=" + (System.nanoTime() - t1) / 1000000 + " hits=" + c);
        System.out.println(fail == 0 ? "ALL PASSED" : fail + " FAILED");
        if (fail > 0) System.exit(1);
    }
}
