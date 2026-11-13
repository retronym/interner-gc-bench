package interner.mechanism;

import interner.ChainedWeak;
import interner.ChunkedStrong;
import interner.ChunkedWeak;
import interner.Interner;
import interner.workload.Node;
import interner.workload.Sym;
import jdk.test.whitebox.WhiteBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where G1 puts each table's arrays, asked of the VM through the WhiteBox API. Runs with a 4 MB region size, as G1
 * picks for an 8 GB heap, so the humongous threshold is 2 MB: a reference array of 512K slots (compressed oops).
 */
class HeapPlacementTest {
    static WhiteBox wb;
    static final Node.Kind REF = Node.KINDS[Node.REF];

    @BeforeAll
    static void whiteBox() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:+WhiteBoxAPI"),
                "needs the mechanism execution's flags: -XX:+WhiteBoxAPI and wb.jar on the boot class path");
        wb = WhiteBox.getWhiteBox();
        assertEquals(4 << 20, wb.g1RegionSize(), "run with -XX:G1HeapRegionSize=4m");
    }

    /** Intern `n` fresh nodes, kept reachable through the returned list. */
    static List<Node> fill(Interner<Node> t, int n) {
        List<Node> keep = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Sym s = new Sym(i);
            keep.add(t.intern(Node.hash(Node.REF, null, s, null), REF, null, s, null));
        }
        return keep;
    }

    @Test
    void chainedBucketArrayBecomesHumongousAndIsOldFromBirth() {
        ChainedWeak<Node> t = new ChainedWeak<>(0x8000);
        List<Node> keep = fill(t, 120_000); // 256K buckets: 1 MB
        Object small = t.backingArrays().get(0);
        assertEquals(256 * 1024, ((Object[]) small).length);
        assertFalse(wb.g1IsHumongous(small));
        assertFalse(wb.isObjectInOldGen(small), "a 1 MB bucket array is allocated young");

        keep.addAll(fill(t, 20_000)); // passes 128K entries: 512K buckets, 2 MB + header
        Object big = t.backingArrays().get(0);
        assertEquals(512 * 1024, ((Object[]) big).length);
        assertTrue(wb.g1IsHumongous(big), "a 2 MB bucket array is humongous");
        assertTrue(wb.isObjectInOldGen(big), "humongous objects are old from birth");

        // the next insert puts a young Entry into that old array: an old-to-young pointer per insert
        keep.addAll(fill(t, 1));
        wb.youngGC();
        assertTrue(wb.isObjectInOldGen(big), "still old after a young GC");
        assertTrue(keep.size() > 0);
    }

    @Test
    void chunkedArraysAreNeverHumongousAndEachRebuildIsYoung() {
        for (Interner<Node> t : List.<Interner<Node>>of(new ChunkedWeak<>(16), new ChunkedStrong<>(16))) {
            List<Node> keep = new ArrayList<>();
            int lastCapacity = capacity(t);
            int rebuilds = 0;
            for (int i = 0; i < 1_100_000; i += 1_000) {
                keep.addAll(fill(t, 1_000));
                if (capacity(t) != lastCapacity) {
                    lastCapacity = capacity(t);
                    rebuilds++;
                    for (Object a : t.backingArrays()) {
                        assertFalse(wb.g1IsHumongous(a), t.getClass().getSimpleName() + " array humongous at capacity " + lastCapacity);
                        assertFalse(wb.isObjectInOldGen(a), t.getClass().getSimpleName() + " fresh array old at capacity " + lastCapacity);
                    }
                }
            }
            assertEquals(1 << 22, lastCapacity, "64 chunks of 64K slots");
            assertTrue(rebuilds >= 10);
        }
    }

    @Test
    void chunksAgeLikeOrdinaryObjectsAndGrowthReplacesThem() {
        ChunkedWeak<Node> t = new ChunkedWeak<>(16);
        List<Node> keep = fill(t, 100_000); // 256K slots: 4 chunks
        assertEquals(1 << 18, t.capacity());
        for (int i = 0; i < 16; i++) wb.youngGC(); // past the maximum tenuring threshold
        for (Object a : t.backingArrays()) assertTrue(wb.isObjectInOldGen(a), "tenured after 16 young GCs");
        keep.addAll(fill(t, 50_000)); // past half load: rebuilt at 512K slots
        assertEquals(1 << 19, t.capacity());
        for (Object a : t.backingArrays()) assertFalse(wb.isObjectInOldGen(a), "a rebuild allocates young chunks");
    }

    @Test
    void rejuvenatingChunksStayYoungAcrossCollections() {
        ChunkedWeak<Node> plain = new ChunkedWeak<>(16), rejuv = new ChunkedWeak<>(16, 1, 0);
        List<Node> keep = fill(plain, 100_000);
        keep.addAll(fill(rejuv, 100_000));
        for (int i = 0; i < 16; i++) {
            wb.youngGC();
            // a few inserts between collections, far from the next growth: the rejuvenating table copies its chunks
            keep.addAll(fill(plain, 10));
            keep.addAll(fill(rejuv, 10));
        }
        assertEquals(plain.capacity(), rejuv.capacity());
        for (Object a : plain.backingArrays()) assertTrue(wb.isObjectInOldGen(a), "plain chunks tenure");
        for (Object a : rejuv.backingArrays()) {
            // element chunks and their spine are kept young; int[] hash chunks hold no references and may tenure
            if (a.getClass() == Object[].class || a.getClass() == Object[][].class) {
                assertFalse(wb.isObjectInOldGen(a), "rejuvenated element chunks are young");
            }
        }
        assertEquals(16L * 4, rejuv.rejuvenatedChunks(), "4 chunks copied after each of 16 collections");

        // with a write threshold, chunks are copied after the collection that follows the fill (25K stores each), and
        // not after the later ones, with 10 stores between collections
        ChunkedWeak<Node> picky = new ChunkedWeak<>(16, 1, 1_000);
        keep.addAll(fill(picky, 100_000));
        for (int i = 0; i < 16; i++) {
            wb.youngGC();
            keep.addAll(fill(picky, 10));
        }
        assertEquals(4, picky.rejuvenatedChunks());
    }

    static int capacity(Interner<Node> t) {
        if (t instanceof ChunkedWeak<Node> w) return w.capacity();
        return ((ChunkedStrong<Node>) t).capacity();
    }
}
