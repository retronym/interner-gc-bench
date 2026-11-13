# interner-gc-bench: plan

## Why

The Scala 3 compiler hash-conses types into global weak interners. Their bucket arrays double at 0.5 load and grow past G1's humongous threshold (half a region: 2 MB at an 8 GB heap), so G1 allocates them straight into the old generation. From then on every insert stores a young `Entry` into an old array, dirtying a card that the next young GC must scan, and the `Entry`s themselves get copied. In dotc this showed up as up to 26 humongous regions, 135K–357K scanned cards per 8 compiles (against 3.5K–7K for a chunked layout), and GC falling from 29% to 11% of CPU cycles once the table was chunked (retronym/scala3#117).

The compiler measurements are convincing but heavy to reproduce and hard to share. This repository isolates the mechanism: the table designs in plain Java, a synthetic workload that allocates the way a compile does, and two kinds of evidence. The whitebox evidence shows the mechanism inside the JVM (humongous placement, card scanning, reference processing, copying) and fails a test if a design loses its property. The blackbox evidence shows what it costs in time per compile and in GC pauses, and whether it gets worse with concurrent compiles in one JVM.

## Key decisions

**One interface, allocation-free lookup.** In dotc a lookup compares the parts of a candidate (`prefix eq`, `tycon eq`, `args.eqElements`) without building the candidate first. A capturing Java lambda would allocate per lookup and distort the comparison, so the interface takes the parts directly: `T intern(int hash, Shape<T> shape, Object a, Object b, Object c)`, where `Shape` is a stateless singleton per node kind that knows how to test `node.matches(a, b, c)` and how to construct the node on a miss. Three parts cover the workload's node kinds (a `Ref(prefix, name)`, an `Applied(tycon, args)` with args as an already-interned list node, a `Refined(parent, name, info)`); wider nodes are cons'd. All five tables implement it.

**The tables are faithful distillations, not reimplementations.** `ChainedWeak` follows dotc's `WeakHashSet` (power-of-two `Entry[]`, `Entry extends WeakReference` with hash and `next`, `ReferenceQueue` drain, doubling at 0.5 load). `ChunkedWeak` is a line-for-line port of `WeakInterner.scala` from #117 (64K-slot chunks of parallel `int[]`/`Object[]`, linear probing across chunks, tombstones, rebuild-at-same-size when live < ¼). The `Strong` variants change only the reference type. `Scoped` (a per-unit strong table) is included as a lower bound and labelled as not semantics-preserving.

**The workload models a compile, not a microbenchmark loop.** One op is one simulated compile with a fresh interner, as a dotc `Run` has. A compile is a sequence of units; each unit interns nodes built from parts and allocates short-lived garbage alongside. Parts are chosen to reproduce the census rather than uniformly at random:
- *Reuse distance:* most parts come from a small recency window of the most recently interned nodes (81% within 64), so hits are on young entries.
- *Cross-unit sharing:* a fraction of parts come from a pool of long-lived "global" nodes (the `TermRef`/`TypeRef`/constant-type analogue), so 45–74% of hits land on entries from earlier units.
- *Liveness:* each unit retains most of what it interns in a unit-level "symbol table" that lives to the end of the compile; about 9% is dropped before the end, so the weak tables see real but modest clearing.
- *Volume:* defaults of ~5M lookups, ~65% hit rate, ~3.7 GB allocated and ~190 MB live per compile, so an 8 GB heap sees 1–3 young GCs per compile.

All of these are parameters (JMH `@Param` and a plain `WorkloadConfig`), the generator is deterministic from a seed, and a `Census` mode measures what the generated workload actually did (lookups, hit rate, reuse distance, cross-unit share, survivors) so the README can show it matches the targets rather than assert it. A `scale` parameter shrinks a compile for tests and smoke runs.

**Concurrency is independent compiles, not a shared table.** The 1/2/4-thread variants run that many compiles at once, each with its own interner, as concurrent `Global`s in one sbt/Bloop JVM would. The hypothesis is that card-scanning cost scales with the number of humongous tables while the chunked layout stays near 1:1.

**Paired runs cancel JIT variance.** Besides one fork per structure, a paired benchmark alternates the structure by iteration in one fork using perflab's `Variants`/`IterationLog`, reporting `paired.<v>.delta%` and the GC-free deltas.

**Whitebox through the HotSpot WhiteBox API.** The Java side of the API is vendored from OpenJDK's `test/lib` at `jdk-25+26` as a GPLv2 module (`wb.jar`) and put on the boot classpath of a separate surefire execution. The mechanism suite forces young GCs with `wb.youngGC()` and asserts, per table at a realistic size: the chained bucket array is humongous (`g1IsHumongous`) and old (`isObjectInOldGen`) right after allocation; no chunked array is ever humongous, and a freshly rebuilt chunk is young. It also asserts that after N inserts and a young GC, the chunked table's chunks are promoted together rather than pinned old from birth. G1 logs (`-Xlog:gc+phases=debug,gc+heap=debug`) are parsed for scanned cards, Merge/Scan Heap Roots, Object Copy, Reference Processing and humongous region counts; JFR (`jdk.GCPhasePause`, `jdk.PromoteObjectInNewPLAB`/`OutsidePLAB`) gives the same from a second source.

**Build and runs.** Maven with the wrapper, JDK 25 Temurin to build, Temurin 25 and Graal 25.4.4 to run. The repo is a perflab project (`perflab.toml`, campaign specs for the separate-fork, paired and threads comparisons, with `gcLog`), so the same campaigns can run on the M4 testbed later. Local smoke runs only until Jason says go.

## Plan

DONE
- Read #117 (`WeakInterner.scala`), the hash-consing write-up and census, and perflab's sample project.
- Plan reviewed (Jason: vendor the WhiteBox API rather than build it from a checkout).
- Maven skeleton (wrapper, JDK 25, JMH, JUnit 5, perflab-jmh), with the vendored WhiteBox API as a GPLv2 module.
- `Interner`/`Shape` and the five tables, with correctness tests.
- Workload generator, `WorkloadConfig`, `Census`; defaults tuned against the dotc census; a unit of the workload is a (unit, phase) scope, as in the census, so a few hundred lookups.
- WhiteBox heap-placement tests and a G1-log mechanism test, run as a separate surefire execution; both checked to fail with humongous chunks.
- `GcLog` and `JfrSummary`, `Driver`.
- `CompileBenchmark` (threads via `-t`), `CompilePairedBenchmark`; perflab.toml; smoke and m4 campaigns.

- Local smoke runs (separate, paired, threads; whitebox via `Driver`), summarised in README.

- `chunked-weak-rejuv` (copy written element chunks after each collection) and a persistent mode (one table across compiles), with a WhiteBox test; probed at `-Xmn256m` (README, "Keeping chunks young").
- GC logs written with `::filecount=0`; `GcLog` refuses rotated logs.
- Compile for release 21: the testbed worker's Maven runs on JDK 21.
- M4 campaigns (separate, paired, threads), `m5-scaling`, `m5-scaling-heap` and `m5-scaling-heap-rejuv`, in the README. `m4-ablation`, `m4-tenure`, `m5-persistent` (persistent against fresh, 2 GB, `-Xmn256m`) and `Driver`'s table-size output, in the README. The paired fork's `chunked-weak` slowdown is profile pollution: the four-way `Interner.intern` site exhausts Graal's inlining budget inside `ChunkedWeak.intern` (JIT recording).

TODO
- Persistent tables at a small young generation (measured: `m5-persistent`, README): dead entries stay in the table until old-generation marking frees their elements, so the table grows and scans tens of millions of cards. Worth a look: clearing entries by compile (an epoch per entry) instead of waiting for reference processing.
- Read `m5-persistent-salted` into the README. Why each G1 cycle clears only a fraction of a persistent table's dead entries (45–850K of ~1.5M per compile, even at IHOP 10).
- A Run-tagged table that drops (or demotes to weak) a Run's entries when the Run ends, measured persistent against `m5-persistent`.
- Void and rerun the `smoke-whitebox` cells (their logs were overwritten by the paired fork that JMH's pattern also matched), or drop that campaign in favour of `Driver`.

Future work
- A nursery-plus-sealed-segments table (write-up PLAN §5); other collectors (Parallel, ZGC) as a control where the humongous effect should vanish; a smaller heap (2 GB) to sharpen the age distribution.
- A fixed `-Xmn` variant, to give the 1–3 young GCs per compile that dotc sees instead of the ~1 this workload sees at 8 GB.

## Open questions (answered by default)

- A fresh interner per compile, as dotc's `Run`; no benchmark keeps one across compiles.
- A full-size compile turned out to take under a second, so smoke runs use full size with fewer iterations; `size=quarter` etc. remain.
- `scoped` is in the whitebox comparison only, not the default JMH set.
