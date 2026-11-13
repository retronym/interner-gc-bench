package interner.bench;

import interner.Interner;
import interner.workload.Compile;
import interner.workload.Node;
import interner.workload.WorkloadConfig;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;

/**
 * One op is one simulated compile against a fresh interner of kind `table`. (No other benchmark name contains
 * "CompileBenchmark", since JMH matches its include pattern anywhere in the name.) Compiles run back to back with no forced
 * GC between them, as in a compile loop, so each sees one or two young GCs at an 8 GB heap.
 *
 * <p>Each thread runs its own compiles (its own seed, its own table), as concurrent compilers in one JVM would; run
 * with `-t 2` or `-t 4` to see how the time per compile grows with concurrency. `-prof gc` gives allocation and GC
 * counts per compile.
 *
 * <p>With `persistent=true` each thread keeps one table (and its hot pool) for the whole fork, as a resident compiler
 * (sbt, Bloop) keeps its interners across compiles. Each compile's own types die with it, so only weak tables can
 * persist. A persistent table lives through many young GCs, which is when its chunks tenure unless rejuvenated
 * (`chunked-weak-rejuv`); a smaller young generation (`-Xmn`) gives the same within a compile.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseG1GC", "-Xms8g", "-Xmx8g"})
@Warmup(iterations = 10)
@Measurement(iterations = 20)
public class CompileBenchmark {
    @Param({"chained-weak", "chained-strong", "chunked-weak", "chunked-strong"})
    public String table;

    @Param({"full"})
    public String size;

    /** Overrides of {@link WorkloadConfig}'s defaults, `key=value;key=value` (JMH splits parameter values at commas). */
    @Param({""})
    public String workload;

    /** Keep one table per thread across compiles (weak tables only). */
    @Param({"false"})
    public boolean persistent;

    /**
     * Call `System.gc()` before each iteration (one compile in single-shot mode), outside the timing. With
     * `-XX:+ExplicitGCInvokesConcurrent` that starts a concurrent cycle when the previous compile's types are dead,
     * as a resident compiler could at the end of each Run.
     */
    @Param({"false"})
    public boolean gcBetween;

    private WorkloadConfig cfg;
    private long seed;
    private Interner<Node> kept;
    private Node[] hot;
    private int compiles;

    @Setup(Level.Trial)
    public void setup(ThreadParams tp) {
        cfg = Sizes.config(size, workload);
        seed = cfg.seed + tp.getThreadIndex();
        if (persistent && !Interner.isWeak(table)) {
            throw new IllegalArgumentException(table + " cannot persist: a strong table would keep every compile's types");
        }
    }

    @Setup(Level.Iteration)
    public void gcBetween() {
        if (gcBetween) System.gc();
    }

    @Benchmark
    public long compile() {
        if (!persistent) return new Compile(cfg, Interner.create(table, cfg.initialCapacity), seed, null).run();
        if (kept == null) kept = Interner.create(table, cfg.initialCapacity);
        Compile c = new Compile(cfg, kept, seed, null, hot, compiles++ * 0x9E3779B9);
        long sum = c.run();
        hot = c.hot();
        return sum;
    }
}
