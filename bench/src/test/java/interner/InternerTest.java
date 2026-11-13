package interner;

import interner.workload.Node;
import interner.workload.Sym;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InternerTest {
    static final List<String> ALL = Interner.NAMES;
    static final List<String> WEAK = List.of("chained-weak", "chunked-weak", "chunked-weak-rejuv", "chunked-weak-lazy");
    static final Shape<Node> REF = Node.KINDS[Node.REF];
    static final Shape<Node> LIST = Node.KINDS[Node.LIST];

    static List<Sym> syms(int n) {
        List<Sym> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Sym(i));
        return out;
    }

    @ParameterizedTest @FieldSource("ALL")
    void internedResultsAreIdentical(String name) {
        Interner<Node> t = Interner.create(name, 16);
        Sym s = new Sym(1), u = new Sym(2);
        Node a = t.intern(Node.hash(Node.REF, null, s, null), REF, null, s, null);
        assertSame(a, t.intern(Node.hash(Node.REF, null, s, null), REF, null, s, null));
        Node b = t.intern(Node.hash(Node.REF, null, u, null), REF, null, u, null);
        assertNotSame(a, b);
        // same parts, different kind: a different element
        Node l = t.intern(Node.hash(Node.LIST, null, s, null), LIST, null, s, null);
        assertNotSame(a, l);
        // cascade: a node built from interned parts is found by the parts' identity
        Node ab = t.intern(Node.hash(Node.LIST, a, b, null), LIST, a, b, null);
        assertSame(ab, t.intern(Node.hash(Node.LIST, a, b, null), LIST, a, b, null));
        assertEquals(4, t.size());
    }

    @ParameterizedTest @FieldSource("ALL")
    void hashCollisions(String name) {
        Interner<Node> t = Interner.create(name, 16);
        List<Sym> ss = syms(2_000);
        List<Node> first = new ArrayList<>();
        for (Sym s : ss) first.add(t.intern(42, REF, null, s, null)); // every element has hash 42
        for (int i = 0; i < ss.size(); i++) assertSame(first.get(i), t.intern(42, REF, null, ss.get(i), null));
        assertEquals(ss.size(), new java.util.HashSet<>(first).size());
        assertEquals(ss.size(), t.size());
    }

    @ParameterizedTest @FieldSource("ALL")
    void growthAcrossChunks(String name) {
        Interner<Node> t = Interner.create(name, 16);
        int n = 300_000; // 1M slots at half load: 16 chunks of 64K
        List<Sym> ss = syms(n);
        Node[] first = new Node[n];
        for (int i = 0; i < n; i++) first[i] = t.intern(ss.get(i).hash * 0x9E3779B1, REF, null, ss.get(i), null);
        for (int i = 0; i < n; i++) assertSame(first[i], t.intern(ss.get(i).hash * 0x9E3779B1, REF, null, ss.get(i), null));
        assertEquals(n, t.size());
        if (t instanceof ChunkedWeak<?> cw) assertEquals(1 << 20, cw.capacity());
        if (t instanceof ChunkedStrong<?> cs) assertEquals(1 << 20, cs.capacity());
        if (t instanceof ChunkedBoxed<?> cb) assertEquals(1 << 20, cb.capacity());
        if (t instanceof ChunkedWeakLazy<?> cl) assertEquals(1 << 20, cl.capacity());
    }

    @ParameterizedTest @FieldSource("ALL")
    void probesCrossChunkBoundariesAndWrapAround(String name) {
        Interner<Node> t = Interner.create(name, 1 << 18); // 4 chunks
        List<Sym> ss = syms(64);
        int lastOfChunk0 = Tables.CHUNK_SIZE - 1, lastSlot = (1 << 18) - 1;
        List<Node> first = new ArrayList<>();
        for (int i = 0; i < 32; i++) first.add(t.intern(lastOfChunk0, REF, null, ss.get(i), null));
        for (int i = 32; i < 64; i++) first.add(t.intern(lastSlot, REF, null, ss.get(i), null));
        for (int i = 0; i < 32; i++) assertSame(first.get(i), t.intern(lastOfChunk0, REF, null, ss.get(i), null));
        for (int i = 32; i < 64; i++) assertSame(first.get(i), t.intern(lastSlot, REF, null, ss.get(i), null));
    }

    @ParameterizedTest @FieldSource("ALL")
    void clearDropsEverything(String name) {
        Interner<Node> t = Interner.create(name, 16);
        Sym s = new Sym(7);
        Node a = t.intern(7, REF, null, s, null);
        t.clear();
        assertEquals(0, t.size());
        assertNotSame(a, t.intern(7, REF, null, s, null));
    }

    @ParameterizedTest @FieldSource("WEAK")
    void collectedElementsAreDropped(String name) throws Exception {
        Interner<Node> t = Interner.create(name, 16);
        int n = 50_000;
        List<Sym> ss = syms(n);
        List<Node> kept = new ArrayList<>();
        WeakReference<Node> probe = null;
        for (int i = 0; i < n; i++) {
            Node x = t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null);
            if (i % 10 == 0) kept.add(x);
            else if (probe == null) probe = new WeakReference<>(x);
        }
        awaitCollected(probe);
        // the next lookup drains the queue
        Sym fresh = new Sym(-1);
        t.intern(-1, REF, null, fresh, null);
        for (int i = 0; i < 20 && t.size() > kept.size() + 1; i++) {
            System.gc();
            Thread.sleep(10);
            t.intern(-1, REF, null, fresh, null);
        }
        assertEquals(kept.size() + 1, t.size());
        for (int i = 0; i < n; i += 10) assertSame(kept.get(i / 10), t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null));
        // a dropped element is made afresh
        Node again = t.intern(1 * 0x9E3779B1, REF, null, ss.get(1), null);
        assertEquals(kept.size() + 2, t.size());
        assertSame(again, t.intern(1 * 0x9E3779B1, REF, null, ss.get(1), null));
    }

    @org.junit.jupiter.api.Test
    void chunkedWeakRebuildsAtTheSameSizeWhenMostlyTombstones() throws Exception {
        ChunkedWeak<Node> t = new ChunkedWeak<>(16);
        List<Sym> ss = syms(400_000);
        WeakReference<Node> probe = null;
        int i = 0;
        for (; i < 200_000; i++) {
            Node x = t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null);
            if (probe == null) probe = new WeakReference<>(x);
        }
        int grown = t.capacity();
        awaitCollected(probe);
        // churn of short-lived elements: tombstones fill the table, but live never reaches a quarter
        for (; i < 400_000; i++) {
            t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null);
            if (i % 50_000 == 0) System.gc();
        }
        assertTrue(t.capacity() <= grown, "capacity " + t.capacity() + " grew past " + grown);
    }

    @org.junit.jupiter.api.Test
    void chunkedWeakLazyReusesClearedSlotsWithoutGrowing() throws Exception {
        ChunkedWeakLazy<Node> t = new ChunkedWeakLazy<>(16);
        List<Sym> ss = syms(400_000);
        WeakReference<Node> probe = null;
        int i = 0;
        for (; i < 200_000; i++) {
            Node x = t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null);
            if (probe == null) probe = new WeakReference<>(x);
        }
        int grown = t.capacity();
        awaitCollected(probe);
        for (; i < 400_000; i++) {
            t.intern(i * 0x9E3779B1, REF, null, ss.get(i), null);
            if (i % 50_000 == 0) System.gc();
        }
        assertTrue(t.capacity() <= grown, "capacity " + t.capacity() + " grew past " + grown);
    }

    @org.junit.jupiter.api.Test
    void scopedDropsAtUnitEnd() {
        Interner<Node> t = Interner.create("scoped", 16);
        Sym s = new Sym(3);
        Node a = t.intern(3, REF, null, s, null);
        assertSame(a, t.intern(3, REF, null, s, null));
        t.endUnit();
        assertNotSame(a, t.intern(3, REF, null, s, null));
    }

    static void awaitCollected(WeakReference<?> probe) throws InterruptedException {
        for (int i = 0; i < 50 && probe.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(probe.get(), "element not collected");
    }
}
