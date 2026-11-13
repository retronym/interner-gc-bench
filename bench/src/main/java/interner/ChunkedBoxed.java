package interner;

import java.util.ArrayList;
import java.util.List;

import static interner.Tables.*;

/**
 * An ablation of {@link ChunkedWeak}: {@link ChunkedStrong} with each element held by a strong {@link Box} that carries
 * the hash, as a `ChunkedWeak.Ref` does. It pays the weak table's allocation per insert and its extra load per probe,
 * but none of the weak reference's costs: no `Reference` constructor, no queue, no discovery or clearing by the GC.
 * So `chunked-strong` → `chunked-boxed` → `chunked-weak` splits the weak table's cost into the box and the weakness.
 */
public final class ChunkedBoxed<K> implements Interner<K> {
    static final class Box {
        final Object elem;
        final int hash;

        Box(Object elem, int hash) {
            this.elem = elem;
            this.hash = hash;
        }
    }

    private final int initialCapacity;
    private int mask;
    private int[][] hashes;
    private Object[][] elems;
    private int count;

    public ChunkedBoxed(int initialCapacity) {
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
                K x = (K) ((Box) e).elem;
                if (shape.matches(x, a, b, c)) return x;
            }
            i = (i + 1) & mask;
        }
        K x = shape.make(hash, a, b, c);
        if ((count + 1) * 2 > mask + 1) rebuild((mask + 1) * 2);
        insert(new Box(x, hash), hash);
        count++;
        return x;
    }

    private void insert(Box box, int hash) {
        int i = hash & mask;
        while (elems[i >>> CHUNK_BITS][i & CHUNK_MASK] != null) i = (i + 1) & mask;
        elems[i >>> CHUNK_BITS][i & CHUNK_MASK] = box;
        hashes[i >>> CHUNK_BITS][i & CHUNK_MASK] = hash;
    }

    private void rebuild(int capacity) {
        Object[][] oldElems = elems;
        mask = capacity - 1;
        elems = newElems(capacity);
        hashes = newHashes(capacity);
        for (Object[] es : oldElems)
            for (Object e : es)
                if (e != null) insert((Box) e, ((Box) e).hash);
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
