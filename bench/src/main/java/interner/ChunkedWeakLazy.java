package interner;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static interner.Tables.*;

/**
 * {@link ChunkedWeak} without a `ReferenceQueue`. Its `Ref`s are registered with no queue, so the GC clears them but
 * never enqueues them: no Reference Handler work, and no drain before each lookup. A cleared slot is found lazily
 * instead: a probe passes over it as over a tombstone, an insert reuses it, and a rebuild drops it. The table no longer
 * knows how many of its elements are live, so it counts them when it is about to rebuild (and for {@link #size()}),
 * then rebuilds as `ChunkedWeak` does: at double the size if live elements fill a quarter of the table, otherwise at
 * the same size.
 */
public final class ChunkedWeakLazy<K> implements Interner<K> {
    static final class Ref extends WeakReference<Object> {
        final int hash;

        Ref(Object elem, int hash) {
            super(elem);
            this.hash = hash;
        }
    }

    private final int initialCapacity;
    private int mask;
    private int[][] hashes;
    private Object[][] elems;
    /** non-null slots: live elements plus cleared refs */
    private int used;

    public ChunkedWeakLazy(int initialCapacity) {
        this.initialCapacity = powerOfTwoAtLeast(Math.max(initialCapacity, 16));
        clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public K intern(int hash, Shape<K> shape, Object a, Object b, Object c) {
        int i = hash & mask;
        while (true) {
            Object e = elems[i >>> CHUNK_BITS][i & CHUNK_MASK];
            if (e == null) break;
            if (hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] == hash) {
                K x = (K) ((Ref) e).get();
                if (x != null && shape.matches(x, a, b, c)) return x;
            }
            i = (i + 1) & mask;
        }
        K x = shape.make(hash, a, b, c);
        if ((used + 1) * 2 > mask + 1) {
            int live = countLive();
            rebuild(live * 4 >= mask + 1 ? (mask + 1) * 2 : mask + 1);
        }
        insert(new Ref(x, hash), hash);
        return x;
    }

    /** Store in the first empty or cleared slot of the probe sequence. */
    private void insert(Ref ref, int hash) {
        int i = hash & mask;
        while (true) {
            Object e = elems[i >>> CHUNK_BITS][i & CHUNK_MASK];
            if (e == null) { used++; break; }
            if (((Ref) e).refersTo(null)) break;
            i = (i + 1) & mask;
        }
        elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = ref;
        hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] = hash;
    }

    private int countLive() {
        int n = 0;
        for (Object[] es : elems)
            for (Object e : es)
                if (e != null && !((Ref) e).refersTo(null)) n++;
        return n;
    }

    private void rebuild(int capacity) {
        Object[][] oldElems = elems;
        mask = capacity - 1;
        elems = newElems(capacity);
        hashes = newHashes(capacity);
        used = 0;
        for (Object[] es : oldElems)
            for (Object e : es)
                if (e != null && !((Ref) e).refersTo(null)) insert((Ref) e, ((Ref) e).hash);
    }

    @Override
    public int size() { return countLive(); }

    @Override
    public void clear() {
        mask = initialCapacity - 1;
        elems = newElems(initialCapacity);
        hashes = newHashes(initialCapacity);
        used = 0;
    }

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
