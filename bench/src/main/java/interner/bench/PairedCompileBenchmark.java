package interner.bench;

import interner.Interner;
import interner.workload.Compile;
import interner.workload.Node;
import interner.workload.WorkloadConfig;
import io.github.retronym.perflab.jmh.IterationLog;
import io.github.retronym.perflab.jmh.Variants;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

import java.util.concurrent.TimeUnit;

/**
 * {@link CompileBenchmark} with the table alternating by iteration within one fork (perflab's paired variants:
 * `variants=cw=chained-weak|kw=chunked-weak`), so every table runs on the same JIT-compiled workload and the
 * comparison is free of fork-to-fork JIT variance. perflab's `PairedProfiler` reports each variant's mean and its delta
 * against the first, per cycle, with and without GC pause time.
 *
 * <p>Iterations of one variant leave garbage for the next: a chained table's humongous array stays in the old
 * generation until a concurrent cycle or eager reclaim frees it. The Williams order puts each variant after each other
 * equally often, so the carry-over is balanced, not removed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseG1GC", "-Xms8g", "-Xmx8g"})
@Warmup(iterations = 12)
@Measurement(iterations = 24)
public class PairedCompileBenchmark {
    @Param({"cw=chained-weak|kw=chunked-weak|ks=chunked-strong"})
    public String variants;

    @Param({"full"})
    public String size;

    @Param({""})
    public String workload;

    private WorkloadConfig cfg;
    private Variants vs;
    private int iteration;
    private String table;

    @Setup(Level.Trial)
    public void setup() {
        cfg = Sizes.config(size, workload);
        vs = Variants.parse(variants);
        IterationLog.reset(vs.names());
    }

    @Setup(Level.Iteration)
    public void next(IterationParams ip) {
        Variants.Variant v = vs.forIteration(iteration++);
        table = v.options().get(0);
        IterationLog.begin(v.name(), ip.getType() == IterationType.MEASUREMENT);
    }

    @TearDown(Level.Iteration)
    public void end() { IterationLog.end(); }

    @Benchmark
    public long compile() {
        Interner<Node> t = Interner.create(table, cfg.initialCapacity);
        return new Compile(cfg, t, cfg.seed, null).run();
    }
}
