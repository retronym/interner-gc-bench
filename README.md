# interner-gc-bench

Hash-consing tables under G1, in isolation: which layout of a long-lived interner costs the least GC, and why.

## The problem

The Scala 3 compiler interns ("hash-conses") types in global weak hash sets that live for a whole compile. Their layout follows a classic chained hash set: one power-of-two bucket array of `Entry` chains, where each `Entry` is a `WeakReference` carrying the hash and a `next` link, doubling at half load. On a large heap that bucket array outgrows half a G1 region (2 MB at an 8 GB heap, so 512K buckets) and becomes *humongous*: G1 allocates it straight into the old generation. From then on every insert stores a young `Entry` into an old array, which dirties a card that the next young collection must scan, and the `Entry`s themselves are evacuated with every survivor copy. The table's useful content is young (nearly all hits land on entries at most one young GC old), but its spine is old, which is the worst case for a generational collector.

In dotc this showed up as up to 26 humongous regions, 135K–357K scanned cards per 8 compiles where a chunked layout scanned 3.5K–7K, and GC time falling from 29% to 11% of CPU cycles once the table was chunked (retronym/scala3#117). An [explainer with diagrams](https://claude.ai/artifact/NWGiMojrz2W4VDLqAKbq6B) walks through the mechanism. This repository takes the compiler out of the picture: the table designs in plain Java, a synthetic workload that allocates and interns the way a compile does, tests that pin the mechanism, and benchmarks that show what it costs.

## The tables

All implement one `Interner<K>` interface. A lookup passes the hash and the parts (`intern(hash, shape, a, b, c)`), and a `Shape` tests a candidate against the parts by identity, so a hit allocates nothing, as dotc's `unique` calls do.

| table | layout |
|---|---|
| `chained-weak` | today's design, after dotc's `WeakHashSet`: `Entry[]` buckets, `Entry extends WeakReference` with hash and `next`, `ReferenceQueue` drained before each lookup, doubling at 0.5 load |
| `chained-strong` | the same chains with strong entries |
| `chunked-weak` | a port of `WeakInterner` from retronym/scala3#117: linear probing over parallel `int[]` hash and `Object[]` element arrays split into 64K-slot chunks (256 KB each), probes crossing chunks; elements held by a slim `WeakReference` carrying the hash; collected slots become tombstones; growth at 0.5 load (live plus tombstones) rebuilds into fresh chunks, at double size if live fills a quarter, else at the same size |
| `chunked-strong` | the same chunks holding elements directly until `clear()` |
| `chunked-weak-rejuv` | `chunked-weak` that keeps its element chunks young: after a collection (seen as a cleared `WeakReference` canary), it replaces each element chunk that took at least 256 stores since the previous one with a copy; `-rejuv-all` copies every element chunk after every collection. See [Keeping chunks young](#keeping-chunks-young) |
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
| entries dead before the compile ends | ~9% | 9.5% |
| allocated per compile | ~3.7 GB | 3.75 GB |
| live heap at the end | ~190 MB | 211 MB |

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

`interner.Driver <table> <compiles>` runs compiles outside JMH for logging or JFR; `interner.gc.GcLog <log>...` totals the young pauses of a log written with `-Xlog:gc*=debug:file=<log>::filecount=0` as above (HotSpot otherwise rotates it at 20 MB and drops the oldest pauses); `interner.gc.JfrSummary <jfr>` gives pause phases and survivor/tenured copies by class from a recording with the `jdk.PromoteObject*` events.

## Blackbox: the cost

`CompileBenchmark` times one compile per op (JMH single-shot), one table per fork, with `-t 1/2/4` for concurrent compiles in one JVM (each thread its own compile and table). `CompilePairedBenchmark` alternates the tables by iteration within one fork through perflab's paired variants, so fork-to-fork JIT variance cancels. Both run at G1, 8 GB.

The repository is a [perflab](https://github.com/retronym/jvm-perf-lab) project (`perflab.toml`, `campaigns/`): `smoke-*` campaigns for a local check, `m4-*` for the testbed. Without perflab, the shaded jar runs as plain JMH: `java -jar bench/target/benchmarks.jar CompileBenchmark -p table=chained-weak,chunked-weak -prof gc`.

### Smoke results

Local, on a laptop (Apple M5 Pro, testbed `m5-pro`), Graal 25.4.4, G1, 8 GB, full-size compiles. One fork per cell and one round, so these say which way things point, not by how much; the `m4-*` campaigns are the measurement.

Separate forks (`smoke-separate`, 6 warm-up and 10 measured compiles; `gc.*` from `-prof gc`, summed over the measured compiles):

| table | ms per compile | young GCs | GC ms |
|---|--:|--:|--:|
| chained-weak | 593 ± 86 | 8 | 975 |
| chained-strong | 553 ± 76 | 8 | 870 |
| chunked-weak | 550 ± 52 | 7 | 200 |
| chunked-strong | 453 ± 33 | 8 | 128 |

The same tables under G1's debug log (`Driver`, 12 compiles back to back, totals over young pauses; mean time of compiles 5–12):

| table | ms per compile | pauses | pause ms | Scan Heap Roots ms | scanned cards | Object Copy ms | copied MB | Ref Proc ms | weak refs discovered | humongous regions |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| chained-weak | 652 | 14 | 1,346 | 1,215 | 2,618,891 | 12,305 | 3,363 | 354 | 29,790,340 | 127 |
| chained-strong | 558 | 12 | 957 | 1,206 | 2,421,820 | 11,852 | 4,376 | 0.8 | 1,012 | 127 |
| chunked-weak | 534 | 11 | 288 | 0.9 | 5,058 | 3,417 | 1,176 | 24 | 1,401,482 | 0 |
| chunked-strong | 470 | 11 | 199 | 1.1 | 5,058 | 2,509 | 901 | 0.8 | 1,275 | 0 |
| scoped | 322 | 11 | 208 | 0.9 | 5,058 | 2,635 | 791 | 0.9 | 1,435 | 0 |

With the chained layout, young pauses scan about 500 times the cards and spend over 1,000 times as long in Scan Heap Roots, and they copy three to five times the bytes, mostly `Entry` objects (JFR's sampled promotion events show `ChainedWeak$Entry` dominating; for the chunked table, the 256 KB chunks themselves). Humongous regions reach 127 because each compile's abandoned bucket arrays stay in the old generation until a concurrent cycle reclaims them. The weak chained table's reference processing (354 ms) is real but secondary, as in dotc. GC pause time falls 4.7× from `chained-weak` to `chunked-weak`, and the time per compile by 7–18%.

Concurrency (`smoke-threads`, ms per compile with 1, 2 and 4 compiles at once in one JVM):

| table | 1 | 2 | 4 | 4 against 1 |
|---|--:|--:|--:|--:|
| chained-weak | 566 ± 99 | 800 ± 197 | 1,255 ± 153 | 2.22× |
| chunked-weak | 529 ± 46 | 624 ± 46 | 1,095 ± 148 | 2.07× |

Both scale poorly: four compiles each allocating at about 6 GB/s share one heap and the GC's worker threads. The chained table degrades a little faster, as the hypothesis predicts, but one fork per cell cannot separate the two.

Paired (`smoke-paired`, all four tables in one fork, 16 measured iterations, so only four cycles): the paired means are `chained-weak` 544 ms, `chained-strong` 508, `chunked-weak` 656, `chunked-strong` 523. That `chunked-weak` is slower here than `chained-weak` (+24%, and +17% with GC pauses removed), against the separate forks, is unexplained. With a per-cycle spread of 32% it may be noise, but its GC-free delta points at mutator code. One candidate is profile pollution: in a shared fork, `Compile.lookup`'s call to `Interner.intern` sees all four tables and is megamorphic, which separate forks never are. The M4 campaign, with more cycles, and a JIT recording will tell.

The `gc.log` of each `smoke-separate`, `smoke-threads` and `smoke-whitebox` cell is not usable. JMH matched `CompileBenchmark` in the old `PairedCompileBenchmark` too, so every cell also ran a second, paired fork that overwrote it (now renamed `CompilePairedBenchmark`). The timings above come from the right fork.

## Keeping chunks young

Chunking keeps the table out of humongous regions, but a chunk is still an ordinary object that tenures once it has lived through enough young GCs, and from then on inserts into it are old-to-young stores again. At an 8 GB heap this does not happen within a compile: G1 keeps the tenuring threshold at 15 and a compile sees one or two young GCs. It does happen when young GCs are frequent, because G1 then lowers the threshold to 1 or 2, and when a table outlives many compiles, as in a resident compiler (`persistent=true`: one table and hot pool per thread across compiles; weak tables only).

`chunked-weak-rejuv` copies an element chunk after a collection if it took enough stores to dirty many of its 512 cards. Hash chunks are `int[]`, which no young GC scans, so they are never copied. Probe with `Driver` (Graal 25.4.4, 2 GB heap, `-Xmn256m`, 10 compiles, mean of compiles 4–10, totals over young pauses):

| table | persistent | ms per compile | pauses | pause ms | scanned cards | copied MB |
|---|---|--:|--:|--:|--:|--:|
| chained-weak | no | 744 | 153 | 1,637 | 6,055,248 | 5,668 |
| chunked-weak | no | 736 | 154 | 1,428 | 3,701,430 | 5,854 |
| chunked-weak-rejuv | no | 673 | 165 | 1,090 | 455,016 | 6,377 |
| chunked-weak-rejuv-all | no | 698 | 164 | 1,099 | 454,586 | 6,326 |
| chained-weak | yes | 1,712 | 153 | 2,092 | 32,036,768 | 5,872 |
| chunked-weak | yes | 1,613 | 154 | 2,246 | 34,876,914 | 6,476 |
| chunked-weak-rejuv | yes | 1,664 | 181 | 2,669 | 46,299,977 | 10,305 |
| chunked-weak-rejuv-all | yes | 6,307 | 3,155 | 32,699 | 453,988,091 | 66,638 |

With frequent young GCs and a table per compile, plain chunks tenure and scan 3.7M cards, most of what the chained table scans. Rejuvenation brings that down eightfold and pause time down by a quarter, for about 9% more copying, and the compile is 9% faster.

A persistent table is a different problem, and rejuvenation makes it worse. With a threshold of 1 or 2, each compile's own types tenure before the compile ends. When the compile is over they are dead, but a weak table learns that only when old-generation marking frees them, so the table holds them, stays large and old, and grows. Every layout then scans tens of millions of cards. Copying every chunk of such a table at every young GC (`-rejuv-all`) fills a 256 MB eden by itself: 3,155 pauses. The write threshold contains that, but it does not help. What a resident compiler needs here is for dead entries to leave the table sooner, not younger chunks.

## Building

JDK 25 to run the tests (`JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem ./mvnw install`); the code is compiled for release 21, so a JDK 21 build tool can package the benchmarks, which fork the JDK given by `-jvm`. The benchmarks depend on `perflab-jmh`, which is not yet published: `mise run install` in a jvm-perf-lab checkout puts it in `~/.m2`.

## Licence

Apache License 2.0 ([LICENSE](LICENSE)), except the `whitebox` module: `WhiteBox.java` and `DiagnosticCommand.java` are copied unchanged from OpenJDK's `test/lib` at `jdk-25+26` and are under the GNU General Public License, version 2 ([whitebox/LICENSE](whitebox/LICENSE), [whitebox/README.md](whitebox/README.md)). They are used only by the tests, loaded on the boot class path at run time.
