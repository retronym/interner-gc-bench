package interner.gc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Totals over the young and mixed pauses of a G1 log written with `-Xlog:gc*=debug` (or at least
 * `gc,gc+heap,gc+phases=debug,gc+phases+ref=debug`). Full collections (`System.gc()` between compiles) are left out.
 *
 * <p>The figures that show the interner's mechanism: scanned cards and Scan Heap Roots time (old-to-young pointers,
 * such as a humongous bucket array pointing at young entries), Object Copy time and copied bytes (survivors, such as
 * entries and chunks), Reference Processing time and WeakReferences discovered (weak tables), and the most humongous
 * regions seen at the start of a pause.
 */
public final class GcLog {
    public static final class Summary {
        public int pauses;
        public double pauseMs, mergeHeapRootsMs, scanHeapRootsMs, objectCopyMs, refProcMs;
        public long scannedCards, dirtyCards, copiedBytes, weakRefsDiscovered;
        public int humongousMax;

        public String header() {
            return "pauses  pause-ms  merge-roots-ms  scan-roots-ms  scanned-cards  dirty-cards  obj-copy-ms  copied-MB  ref-proc-ms  weak-refs  humongous-max";
        }

        public String row() {
            return String.format(Locale.ROOT, "%6d  %8.1f  %14.1f  %13.1f  %13d  %11d  %11.1f  %9.1f  %11.1f  %9d  %13d",
                    pauses, pauseMs, mergeHeapRootsMs, scanHeapRootsMs, scannedCards, dirtyCards, objectCopyMs,
                    copiedBytes / 1e6, refProcMs, weakRefsDiscovered, humongousMax);
        }

        public Map<String, Number> asMap() {
            Map<String, Number> m = new java.util.LinkedHashMap<>();
            m.put("pauses", pauses);
            m.put("pauseMs", pauseMs);
            m.put("mergeHeapRootsMs", mergeHeapRootsMs);
            m.put("scanHeapRootsMs", scanHeapRootsMs);
            m.put("scannedCards", scannedCards);
            m.put("dirtyCards", dirtyCards);
            m.put("objectCopyMs", objectCopyMs);
            m.put("copiedBytes", copiedBytes);
            m.put("refProcMs", refProcMs);
            m.put("weakRefsDiscovered", weakRefsDiscovered);
            m.put("humongousMax", humongousMax);
            return m;
        }
    }

    private static final Pattern GC_ID = Pattern.compile("\\bGC\\((\\d+)\\)\\s+(.*)$");
    private static final Pattern PAUSE = Pattern.compile("^Pause (Young|Full|Remark|Cleanup)\\b.*?([\\d.]+)ms$");
    private static final Pattern SUM = Pattern.compile("Sum: ([\\d.]+)");
    private static final Pattern MS = Pattern.compile(": ([\\d.]+)ms$");
    private static final Pattern HUMONGOUS = Pattern.compile("^Humongous regions: (\\d+)->(\\d+)");
    private static final Pattern WEAK = Pattern.compile("^WeakReference Discovered: (\\d+)");

    public static Summary parse(Path log) throws IOException {
        // first pass: which GC ids are young or mixed pauses
        Set<String> young = new HashSet<>();
        Map<String, Double> pauseMs = new HashMap<>();
        for (String line : Files.readAllLines(log)) {
            Matcher g = GC_ID.matcher(line);
            if (!g.find()) continue;
            Matcher p = PAUSE.matcher(g.group(2).trim());
            if (p.find() && p.group(1).equals("Young")) {
                young.add(g.group(1));
                pauseMs.put(g.group(1), Double.parseDouble(p.group(2)));
            }
        }
        Summary s = new Summary();
        s.pauses = young.size();
        for (double ms : pauseMs.values()) s.pauseMs += ms;
        for (String line : Files.readAllLines(log)) {
            Matcher g = GC_ID.matcher(line);
            if (!g.find() || !young.contains(g.group(1))) continue;
            String rest = g.group(2).trim();
            if (rest.startsWith("Merge Heap Roots:")) s.mergeHeapRootsMs += ms(rest);
            else if (rest.startsWith("Scan Heap Roots (ms):")) s.scanHeapRootsMs += sum(rest);
            else if (rest.startsWith("Scanned Cards:")) s.scannedCards += (long) sum(rest);
            else if (rest.startsWith("Dirty Cards:")) s.dirtyCards += (long) sum(rest);
            else if (rest.startsWith("Object Copy (ms):")) s.objectCopyMs += sum(rest);
            else if (rest.startsWith("Copied Bytes:")) s.copiedBytes += (long) sum(rest);
            else if (rest.startsWith("Reference Processing:") && line.contains("gc,phases ")) s.refProcMs += ms(rest);
            else {
                Matcher h = HUMONGOUS.matcher(rest);
                if (h.find()) s.humongousMax = Math.max(s.humongousMax, Integer.parseInt(h.group(1)));
                Matcher w = WEAK.matcher(rest);
                if (w.find()) s.weakRefsDiscovered += Long.parseLong(w.group(1));
            }
        }
        return s;
    }

    private static double sum(String s) {
        Matcher m = SUM.matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    private static double ms(String s) {
        Matcher m = MS.matcher(s);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    /** `GcLog <log>...`: one row per log. */
    public static void main(String[] args) throws IOException {
        Summary first = null;
        for (String a : args) {
            Summary s = parse(Path.of(a));
            if (first == null) {
                first = s;
                System.out.println(String.format("%-40s", "log") + s.header());
            }
            System.out.println(String.format("%-40s", Path.of(a).getFileName()) + s.row());
        }
    }
}
