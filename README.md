# interner-gc-bench

Hash-consing tables under G1, in isolation: which layout of a long-lived interner costs the least GC, and why.

## The problem

The Scala 3 compiler interns ("hash-conses") types in global weak hash sets that live for a whole compile. Their layout follows a classic chained hash set: one power-of-two bucket array of `Entry` chains, where each `Entry` is a `WeakReference` carrying the hash and a `next` link, doubling at half load. On a large heap that bucket array outgrows half a G1 region (2 MB at an 8 GB heap, so 512K buckets) and becomes *humongous*: G1 allocates it straight into the old generation. From then on every insert stores a young `Entry` into an old array, which dirties a card that the next young collection must scan, and the `Entry`s themselves are evacuated with every survivor copy. The table's useful content is young (nearly all hits land on entries at most one young GC old), but its spine is old, which is the worst case for a generational collector.

In dotc this showed up as up to 26 humongous regions, 135K–357K scanned cards per 8 compiles where a chunked layout scanned 3.5K–7K, and GC time falling from 29% to 11% of CPU cycles once the table was chunked (retronym/scala3#117). This repository takes the compiler out of the picture: the table designs in plain Java, a synthetic workload that allocates and interns the way a compile does, tests that pin the mechanism, and benchmarks that show what it costs.

## The tables

All implement one `Interner<K>` interface. A lookup passes the hash and the parts (`intern(hash, shape, a, b, c)`), and a `Shape` tests a candidate against the parts by identity, so a hit allocates nothing, as dotc's `unique` calls do.

| table | layout |
|---|---|
| `chained-weak` | today's design, after dotc's `WeakHashSet`: `Entry[]` buckets, `Entry extends WeakReference` with hash and `next`, `ReferenceQueue` drained before each lookup, doubling at 0.5 load |
| `chained-strong` | the same chains with strong entries |
| `chunked-weak` | a port of `WeakInterner` from retronym/scala3#117: linear probing over parallel `int[]` hash and `Object[]` element arrays split into 64K-slot chunks (256 KB each), probes crossing chunks; elements held by a slim `WeakReference` carrying the hash; collected slots become tombstones; growth at 0.5 load (live plus tombstones) rebuilds into fresh chunks, at double size if live fills a quarter, else at the same size |
| `chunked-strong` | the same chunks holding elements directly until `clear()` |
| `scoped` | a lower bound, not a candidate: a strong table dropped at the end of every unit. It loses every cross-unit hit, and in a compiler it would break the identity of interned types across units |

No chunk is humongous, and every growth allocates its chunks young, so inserts write young-to-young and a young GC has no old array to scan.

## The workload

One op is one simulated compile against a fresh table. A compile is a sequence of scopes, each a (unit, phase) pair as in the census, and each step either re-interns an existing type from its parts (spine first, a hit at every level) or builds a new one from existing parts and fresh symbols (misses, with the occasional accidental hit on a shared suffix). Existing nodes are picked mostly from the most recent inserts (geometric reuse distance), and otherwise from earlier scopes (geometric distance in scopes) or a hot pool made before the first scope. Most new nodes are retained, together with a few "tree" objects referring to them, until the compile ends; the rest die once no recent pick or retained node reaches them. Every lookup also allocates short-lived garbage. The generator is deterministic from a seed, and the steps are the same for every table (a weak table may re-create a dead node where a strong one hits; the sequence of steps does not change).

Every knob is a parameter (`WorkloadConfig`, or JMH's `-p workload=key=value;key=value`), and `interner.workload.Census` measures what a generated compile actually did. Against the dotc census (`chained-weak`, defaults, Temurin 25, 8 GB):

| | target (dotc census) | generated |
|---|--:|--:|
| lookups per compile | 2.5–6M | 5.0M |
| hit rate | 50–80% | 66% |
| hits on an earlier scope or the hot pool | 45–74% | 61% |
| same-scope hits within 64 inserts | 81% | 84% |
| hits on an entry at most one young GC old | 93–100% | 100% |
| entries dead before the compile ends | ~9% | 7% |
| allocated per compile | ~3.7 GB | 3.75 GB |
| live heap at the end | ~190 MB | 205 MB |

The 81%-within-64 target cannot hold for all hits when most of them are cross-scope, so it is applied to same-scope hits; cross-scope hits are far in insert distance but near in time. With compiles back to back (no forced GC between them) an 8 GB heap sees about one young GC per compile, against 1–3 in dotc.

## Whitebox: the mechanism

`./mvnw install` runs three test suites. `InternerTest` and `CensusTest` check correctness (hash collisions, growth across chunk boundaries, wraparound, rebuilds at the same size, dropped collected elements, identity of results) and the workload's shape. The mechanism suite (`bench/src/test/java/interner/mechanism`) runs in its own JVM with a 4 MB region size, as G1 picks for an 8 GB heap:

- `HeapPlacementTest` asks the VM through the HotSpot WhiteBox API (`-XX:+WhiteBoxAPI`, the vendored `whitebox` module on the boot class path) where each array lives. The chained bucket array is young and ordinary at 256K buckets and humongous and old from birth at 512K, and stays old across young GCs. No chunked array is ever humongous, every rebuild's chunks are young, and chunks tenure like ordinary objects until the next growth replaces them.
- `GcLogMechanismTest` runs four compiles per table in a child JVM with a fixed young generation and reads G1's debug log. Chained tables must hold humongous regions and scan more than 20 times the cards of chunked ones; only weak tables may discover references by the hundred thousand.

Both fail if the chunk size is raised to 1M slots (a humongous chunk), which is the property the chunked design exists for.

From the mechanism suite's child JVMs (Temurin 25, 2 GB heap, 512 MB young, 4 compiles at a quarter of full size, 7 young pauses each):

| table | pause ms | Scan Heap Roots ms | scanned cards | Object Copy ms | copied MB | Ref Proc ms | weak refs discovered | humongous regions |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| chained-weak | 117 | 63.6 | 147,137 | 1,127 | 299 | 22.8 | 2,297,530 | 12 |
| chained-strong | 80 | 71.8 | 148,761 | 904 | 352 | 0.4 | 32 | 12 |
| chunked-weak | 53 | 0.3 | 1,214 | 558 | 192 | 4.2 | 190,767 | 0 |
| chunked-strong | 38 | 0.4 | 1,732 | 403 | 165 | 0.4 | 58 | 0 |

`interner.Driver <table> <compiles>` runs compiles outside JMH for logging or JFR; `interner.gc.GcLog <log>...` totals a `-Xlog:gc*=debug` log's young pauses as above; `interner.gc.JfrSummary <jfr>` gives pause phases and survivor/tenured copies by class from a recording with the `jdk.PromoteObject*` events.

## Blackbox: the cost

`CompileBenchmark` times one compile per op (JMH single-shot), one table per fork, with `-t 1/2/4` for concurrent compiles in one JVM (each thread its own compile and table). `PairedCompileBenchmark` alternates the tables by iteration within one fork through perflab's paired variants, so fork-to-fork JIT variance cancels. Both run at G1, 8 GB.

The repository is a [perflab](https://github.com/retronym/jvm-perf-lab) project (`perflab.toml`, `campaigns/`): `smoke-*` campaigns for a local check, `m4-*` for the testbed. Without perflab, the shaded jar runs as plain JMH: `java -jar bench/target/benchmarks.jar CompileBenchmark -p table=chained-weak,chunked-weak -prof gc`.

SMOKE-RESULTS

## Building

JDK 25 (`JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem ./mvnw install`). The benchmarks depend on `perflab-jmh`, which is not yet published: `mise run install` in a jvm-perf-lab checkout puts it in `~/.m2`.

## Licence

Apache License 2.0 ([LICENSE](LICENSE)), except the `whitebox` module: `WhiteBox.java` and `DiagnosticCommand.java` are copied unchanged from OpenJDK's `test/lib` at `jdk-25+26` and are under the GNU General Public License, version 2 ([whitebox/LICENSE](whitebox/LICENSE), [whitebox/README.md](whitebox/README.md)). They are used only by the tests, loaded on the boot class path at run time.
