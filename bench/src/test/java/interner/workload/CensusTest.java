package interner.workload;

import interner.Interner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The generated workload has the shape the defaults promise, at a tenth of the size. */
class CensusTest {
    static WorkloadConfig small() { return WorkloadConfig.parse("scale=0.1"); }

    @Test
    void shapeMatchesTheTargets() {
        Census c = Census.measure(small(), "chained-weak");
        assertTrue(c.lookups >= 500_000 && c.lookups < 500_010, "lookups " + c.lookups);
        assertEquals(0.65, c.hitRate(), 0.03, "hit rate");
        double cross = (double) c.crossUnit / c.hitSteps;
        assertTrue(cross > 0.45 && cross < 0.74, "cross-unit share of hit steps " + cross);
        double near = (double) c.sameUnitWithin64 / c.sameUnit;
        assertTrue(near > 0.7 && near < 0.9, "same-unit hits within 64 inserts " + near);
        double dead = 1 - (double) c.survivorsAtEnd / c.inserts;
        assertTrue(dead > 0.03 && dead < 0.15, "dead before the end " + dead);
        double allocPerLookup = (double) c.allocatedBytes / c.lookups;
        assertTrue(allocPerLookup > 600 && allocPerLookup < 900, "bytes allocated per lookup " + allocPerLookup);
    }

    @Test
    void deterministicAndTheSameStepsForEveryTable() {
        WorkloadConfig cfg = small();
        long ref = -1, inserts = -1;
        for (String name : new String[] {"chained-weak", "chained-strong", "chunked-weak", "chunked-strong"}) {
            Compile c = new Compile(cfg, Interner.create(name, cfg.initialCapacity), cfg.seed, null);
            long sum = c.run();
            if (ref == -1) { ref = sum; inserts = c.inserted(); }
            assertEquals(ref, sum, name);
            assertEquals(inserts, c.inserted(), name);
        }
        Compile other = new Compile(cfg, Interner.create("chunked-strong", cfg.initialCapacity), cfg.seed + 1, null);
        assertNotEquals(ref, other.run());
    }

    @Test
    void scopedLosesCrossUnitHits() {
        WorkloadConfig cfg = small();
        Compile global = new Compile(cfg, Interner.create("chunked-strong", cfg.initialCapacity), cfg.seed, null);
        global.run();
        Compile scoped = new Compile(cfg, Interner.create("scoped", cfg.initialCapacity), cfg.seed, null);
        scoped.run();
        assertTrue(scoped.hits() < global.hits() * 0.7, scoped.hits() + " vs " + global.hits());
    }
}
