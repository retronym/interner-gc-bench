package interner;

import java.util.ArrayList;
import java.util.List;

import static interner.Tables.*;

/**
 * {@link ChunkedWeak} with strong slots: elements are stored directly and held until {@link #clear()}, so there are no
 * `Ref`s, no tombstones and no reference processing. Growth doubles at half load.
 */
public final class ChunkedStrong<K> implements Interner<K> {
    private final int initialCapacity;
    private int mask;
    private int[][] hashes;
    private Object[][] elems;
    private int count;

    public ChunkedStrong(int initialCapacity) {
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
            if (hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] == hash && shape.matches((K) e, a, b, c)) return (K) e;
            i = (i + 1) & mask;
        }
        K x = shape.make(hash, a, b, c);
        if ((count + 1) * 2 > mask + 1) {
            rebuild((mask + 1) * 2);
            i = hash & mask;
            while (elems[i >>> CHUNK_BITS][i & CHUNK_MASK] != null) i = (i + 1) & mask;
        }
        elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = x;
        hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] = hash;
        count++;
        return x;
    }

    private void rebuild(int capacity) {
        Object[][] oldElems = elems;
        int[][] oldHashes = hashes;
        mask = capacity - 1;
        elems = newElems(capacity);
        hashes = newHashes(capacity);
        for (int c = 0; c < oldElems.length; c++) {
            Object[] es = oldElems[c];
            int[] hs = oldHashes[c];
            for (int j = 0; j < es.length; j++) {
                Object e = es[j];
                if (e != null) {
                    int h = hs[j];
                    int i = h & mask;
                    while (elems[i >>> CHUNK_BITS][i & CHUNK_MASK] != null) i = (i + 1) & mask;
                    elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = e;
                    hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] = h;
                }
            }
        }
    }

    @Override
    public int size() { return count; }

    @Override
    public void clear() {
        mask = initialCapacity - 1;
        elems = newElems(initialCapacity);
        hashes = newHashes(initialCapacity);
        count = 0;
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
