package interner.workload;

import interner.Interner;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

/**
 * What a generated compile actually did, against the targets in {@link WorkloadConfig}: lookups and hit rate, where
 * hits land (same unit or earlier, reuse distance in inserts, age in young GCs), allocation, young GCs, live heap at
 * the end, and the share of interned nodes that died before the end.
 *
 * <p>Reuse distance and age are recorded per hit step (the top-level target), not per lookup of its spine. A cross-unit
 * target's distance is a lower bound (inserts since its unit ended), and its age counts the young GCs between the
 * starts of the two units.
 */
public final class Census {
    public long lookups, hits, inserts, built, retained;
    public long hitSteps, crossUnit, hot, within64, sameUnit, sameUnitWithin64, ageAtMostOneGc;
    public long allocatedBytes, youngGcs, liveBytesAfter, survivorsAtEnd = -1;
    public double millis;

    private int[] gcAtUnit;
    private long gcAtStart, allocAtStart, nanosAtStart;

    void compileStart(int units) {
        gcAtUnit = new int[units];
        gcAtStart = youngGcCount();
        allocAtStart = allocatedBytes();
        nanosAtStart = System.nanoTime();
    }

    void unitStart(int unit) { gcAtUnit[unit] = (int) (youngGcCount() - gcAtStart); }

    void hitStep(int unit, int targetUnit, int distance) {
        hitSteps++;
        boolean same = targetUnit == unit;
        if (!same) crossUnit++;
        if (targetUnit < 0) hot++;
        if (distance <= 64) within64++;
        if (same) {
            sameUnit++;
            if (distance <= 64) sameUnitWithin64++;
        }
        int now = unit < 0 ? 0 : gcAtUnit[unit];
        int then = targetUnit < 0 ? 0 : gcAtUnit[targetUnit];
        if (now - then <= 1) ageAtMostOneGc++;
    }

    void compileEnd(Compile c) {
        millis = (System.nanoTime() - nanosAtStart) / 1e6;
        lookups = c.lookups();
        hits = c.hits();
        inserts = c.made();
        retained = c.retainedNodes();
        built = c.inserted();
        allocatedBytes = allocatedBytes() - allocAtStart;
        youngGcs = youngGcCount() - gcAtStart;
    }

    static long youngGcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().contains("Young")) n += gc.getCollectionCount();
        }
        return n;
    }

    static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    static long usedHeapAfterGc() {
        for (int i = 0; i < 3; i++) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** Run one compile against a fresh `tableName` table and measure it. */
    public static Census measure(WorkloadConfig cfg, String tableName) {
        long baseline = usedHeapAfterGc();
        Census census = new Census();
        Interner<Node> table = Interner.create(tableName, cfg.initialCapacity);
        Compile compile = new Compile(cfg, table, cfg.seed, census);
        compile.run();
        census.liveBytesAfter = usedHeapAfterGc() - baseline;
        if (tableName.endsWith("-weak")) {
            // the Reference Handler thread enqueues cleared references after the GC; a lookup then drains the queue,
            // so size() counts only survivors once it stops changing
            Sym s = new Sym(0);
            Node.Kind ref = Node.kinds()[Node.REF];
            int last = -1;
            for (int i = 0; i < 20; i++) {
                try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                table.intern(0, ref, null, s, null);
                int size = table.size();
                if (size == last) break;
                last = size;
            }
            census.survivorsAtEnd = table.size() - 1;
        }
        census.liveBytesAfter += 0L * compile.touch(); // keep the compile reachable through the measurement
        return census;
    }

    public double hitRate() { return (double) hits / lookups; }

    public String report(String label) {
        StringBuilder sb = new StringBuilder();
        java.util.function.BiConsumer<String, String> row = (k, v) -> sb.append(String.format(Locale.ROOT, "  %-44s %s%n", k, v));
        sb.append(label).append('\n');
        row.accept("lookups", String.format(Locale.ROOT, "%,d", lookups));
        row.accept("hit rate", pct(hits, lookups));
        row.accept("inserts (misses)", String.format(Locale.ROOT, "%,d", inserts));
        row.accept("hit steps on an earlier unit or the hot pool", pct(crossUnit, hitSteps));
        row.accept("  ... on the hot pool", pct(hot, hitSteps));
        row.accept("hit steps within 64 inserts", pct(within64, hitSteps));
        row.accept("  same-unit hit steps within 64 inserts", pct(sameUnitWithin64, sameUnit));
        row.accept("hit steps on a node at most one young GC old", pct(ageAtMostOneGc, hitSteps));
        row.accept("built nodes retained by units", pct(retained, built));
        if (survivorsAtEnd >= 0) row.accept("nodes dead before the end (weak table)", pct(inserts - survivorsAtEnd, inserts));
        row.accept("allocated", String.format(Locale.ROOT, "%,.0f MB", allocatedBytes / 1e6));
        row.accept("live heap at end", String.format(Locale.ROOT, "%,.0f MB", liveBytesAfter / 1e6));
        row.accept("young GCs", Long.toString(youngGcs));
        row.accept("time", String.format(Locale.ROOT, "%,.0f ms", millis));
        return sb.toString();
    }

    private static String pct(long n, long d) {
        return d == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * n / d);
    }

    /** `Census [table,...] [key=value,...]`: one compile per table, after one warm-up compile. */
    public static void main(String[] args) {
        List<String> tables = args.length > 0 ? List.of(args[0].split(",")) : List.of("chained-weak");
        WorkloadConfig cfg = WorkloadConfig.parse(args.length > 1 ? args[1] : "");
        System.out.println("workload: " + cfg);
        measure(cfg, tables.get(0));
        for (String t : tables) System.out.print(measure(cfg, t).report(t));
    }
}
