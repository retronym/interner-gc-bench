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

    private WorkloadConfig cfg;
    private long seed;

    @Setup(Level.Trial)
    public void setup(ThreadParams tp) {
        cfg = Sizes.config(size, workload);
        seed = cfg.seed + tp.getThreadIndex();
    }

    @Benchmark
    public long compile() {
        Interner<Node> t = Interner.create(table, cfg.initialCapacity);
        return new Compile(cfg, t, seed, null).run();
    }
}
