package interner;

import interner.workload.Compile;
import interner.workload.Node;
import interner.workload.WorkloadConfig;

import java.util.Locale;

/**
 * `Driver <table> <compiles> [key=value,...]`: run that many compiles back to back in one thread, as a compile loop
 * would (no forced GC between them unless `-Dgc.between=true`; one table kept across them with `-Dpersistent=true`),
 * and print the time of each. For runs under GC logging
 * or JFR outside JMH.
 */
public final class Driver {
    public static void main(String[] args) {
        String table = args[0];
        int compiles = Integer.parseInt(args[1]);
        WorkloadConfig cfg = WorkloadConfig.parse(args.length > 2 ? args[2] : "");
        boolean persistent = Boolean.getBoolean("persistent");
        long sink = 0;
        Interner<Node> kept = null;
        Node[] hot = null;
        for (int i = 0; i < compiles; i++) {
            if (Boolean.getBoolean("gc.between")) System.gc();
            long t0 = System.nanoTime();
            Interner<Node> t = persistent ? (kept == null ? kept = Interner.create(table, cfg.initialCapacity) : kept)
                    : Interner.create(table, cfg.initialCapacity);
            Compile c = new Compile(cfg, t, cfg.seed, null, hot);
            sink += c.run();
            if (persistent) hot = c.hot();
            System.out.printf(Locale.ROOT, "%s compile %d: %.1f ms%n", table, i, (System.nanoTime() - t0) / 1e6);
        }
        if (sink == 42) System.out.println();
    }
}
