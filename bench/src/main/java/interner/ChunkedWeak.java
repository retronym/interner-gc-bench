package interner;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static interner.Tables.*;

/**
 * A port of `WeakInterner` from retronym/scala3#117: open addressing with linear probing over parallel arrays of
 * hashes and elements, split into chunks of 64K slots (256 KB each). One probe sequence runs across the chunks, so a
 * lookup costs what it would in one flat array, but no array reaches G1's humongous threshold. Growth rebuilds the
 * table into fresh chunks, which are allocated young.
 *
 * <p>Each element is held by a {@link Ref}, a `WeakReference` that also carries the hash. A collected element's slot
 * becomes a tombstone, and the next rebuild drops it. The table rebuilds when live elements plus tombstones would pass
 * half the slots: at double the size if live elements fill a quarter of the table, otherwise at the same size.
 *
 * <p>With `rejuvenateAfter > 0` (the `chunked-weak-rejuv` tables), chunks are also kept young between rebuilds. A chunk
 * that lives through enough young GCs is tenured, and from then on every insert into it is an old-to-young store, as
 * with a humongous bucket array; under a small young generation G1 lowers the tenuring threshold to 1 or 2 and that
 * happens within a compile. So the table counts GCs (a `WeakReference` canary to a garbage object, cleared by every
 * collection), and on the first insert after a chunk has seen `rejuvenateAfter` of them it replaces the chunk with a
 * copy, which is young. Only element chunks are copied: a hash chunk is an `int[]`, which no young GC scans. And only
 * chunks that took at least `minWrites` stores since the previous collection: a chunk is 512 cards, and one that is
 * written rarely costs less to scan than to copy at every young GC (the copy, and then the GC's copy of the copy).
 */
public final class ChunkedWeak<K> implements Interner<K> {
    static final class Ref extends WeakReference<Object> {
        final int hash;

        Ref(Object elem, int hash, ReferenceQueue<Object> queue) {
            super(elem, queue);
            this.hash = hash;
        }
    }

    static final Object TOMBSTONE = new Object();

    private final int initialCapacity;
    private int mask;
    private int[][] hashes;
    private Object[][] elems;
    /** live elements plus tombstones */
    private int used;
    private int live;
    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    /** Young GCs a chunk may see before it is copied; 0 for never. */
    private final int rejuvenateAfter;
    /** Stores into a chunk since the last collection that make it worth copying. */
    private final int minWrites;
    private int[] writes;
    /** Collections seen, counted by `canary` being cleared. */
    private int epoch;
    private WeakReference<Object> canary = new WeakReference<>(new Object());
    /** The epoch each chunk (and the spine, `spineBorn`) was allocated in. */
    private int[] born;
    private int spineBorn;
    private long rejuvenatedChunks;

    public ChunkedWeak(int initialCapacity) {
        this(initialCapacity, 0, 0);
    }

    public ChunkedWeak(int initialCapacity, int rejuvenateAfter, int minWrites) {
        this.initialCapacity = powerOfTwoAtLeast(Math.max(initialCapacity, 16));
        this.rejuvenateAfter = rejuvenateAfter;
        this.minWrites = minWrites;
        clear();
    }

    @SuppressWarnings("unchecked")
    private static <K> K deref(Object e) {
        return e == TOMBSTONE ? null : (K) ((Ref) e).get();
    }

    @Override
    public K intern(int hash, Shape<K> shape, Object a, Object b, Object c) {
        removeStaleEntries();
        int i = hash & mask;
        while (true) {
            Object e = elems[i >>> CHUNK_BITS][i & CHUNK_MASK];
            if (e == null) break;
            if (hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] == hash) {
                K x = deref(e);
                if (x != null && shape.matches(x, a, b, c)) return x;
            }
            i = (i + 1) & mask;
        }
        K x = shape.make(hash, a, b, c);
        add(x, hash);
        return x;
    }

    private void add(Object elem, int hash) {
        if (rejuvenateAfter > 0 && canary.refersTo(null)) newEpoch();
        if ((used + 1) * 2 > mask + 1) rebuild(live * 4 >= mask + 1 ? (mask + 1) * 2 : mask + 1);
        insert(new Ref(elem, hash, queue), hash);
        used++;
        live++;
    }

    private void insert(Ref ref, int hash) {
        int i = hash & mask;
        while (true) {
            Object e = elems[i >>> CHUNK_BITS][i & CHUNK_MASK];
            if (e == null || e == TOMBSTONE) break;
            i = (i + 1) & mask;
        }
        elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = ref;
        hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] = hash;
        writes[i >>> CHUNK_BITS]++;
    }

    /** A collection has happened since the last insert: copy the chunks (and the spine) that are about to tenure. */
    private void newEpoch() {
        epoch++;
        canary = new WeakReference<>(new Object());
        if (epoch - spineBorn >= rejuvenateAfter) {
            elems = elems.clone();
            spineBorn = epoch;
        }
        for (int c = 0; c < born.length; c++) {
            if (epoch - born[c] >= rejuvenateAfter && writes[c] >= minWrites) {
                elems[c] = elems[c].clone();
                born[c] = epoch;
                rejuvenatedChunks++;
            }
            writes[c] = 0;
        }
    }

    private void resetBirths() {
        born = new int[elems.length];
        writes = new int[elems.length];
        java.util.Arrays.fill(born, epoch);
        spineBorn = epoch;
    }

    /** Chunks copied to keep them young, for tests and reports. */
    public long rejuvenatedChunks() { return rejuvenatedChunks; }

    /** Reinsert the live elements into fresh chunks of `capacity` slots. */
    private void rebuild(int capacity) {
        Object[][] oldElems = elems;
        mask = capacity - 1;
        elems = newElems(capacity);
        hashes = newHashes(capacity);
        resetBirths();
        used = 0;
        live = 0;
        for (Object[] es : oldElems) {
            for (Object e : es) {
                if (e != null && deref(e) != null) {
                    Ref ref = (Ref) e;
                    insert(ref, ref.hash);
                    used++;
                    live++;
                }
            }
        }
    }

    /** Turn the slots of collected elements into tombstones. */
    private void removeStaleEntries() {
        Object stale;
        while ((stale = queue.poll()) != null) {
            Ref ref = (Ref) stale;
            int i = ref.hash & mask;
            while (true) {
                Object e = elems[i >>> CHUNK_BITS][i & CHUNK_MASK];
                if (e == null) break; // a rebuild already dropped it
                if (e == ref) {
                    elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = TOMBSTONE;
                    live--;
                    break;
                }
                i = (i + 1) & mask;
            }
        }
    }

    @Override
    public int size() { return live; }

    @Override
    public void clear() {
        mask = initialCapacity - 1;
        elems = newElems(initialCapacity);
        hashes = newHashes(initialCapacity);
        resetBirths();
        used = 0;
        live = 0;
        while (queue.poll() != null) {}
    }

    /** The number of slots. */
    public int capacity() { return mask + 1; }

    @Override
    public List<Object> backingArrays() {
        List<Object> all = new ArrayList<>();
        all.add(elems);
        all.add(hashes);
        all.addAll(List.of(elems));
        all.addAll(List.of(hashes));
        return all;
    }
}
