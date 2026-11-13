package interner;

import java.util.List;

/**
 * A lower bound, not a candidate: a strong {@link ChunkedStrong} table that is dropped at the end of every unit. It
 * loses every cross-unit hit, and in a compiler it would break the identity of interned types across units (types
 * built in different units from equal parts become distinct twins), so it only shows how cheap interning can be when
 * the table never outlives a unit.
 */
public final class Scoped<K> implements Interner<K> {
    private final int initialCapacity;
    private ChunkedStrong<K> table;

    /** `initialCapacity` is capped at 1K slots: the table is made afresh for every unit. */
    public Scoped(int initialCapacity) {
        this.initialCapacity = Math.min(initialCapacity, 1024);
        this.table = new ChunkedStrong<>(this.initialCapacity);
    }

    @Override
    public K intern(int hash, Shape<K> shape, Object a, Object b, Object c) {
        return table.intern(hash, shape, a, b, c);
    }

    @Override
    public void endUnit() { table = new ChunkedStrong<>(initialCapacity); }

    @Override
    public int size() { return table.size(); }

    @Override
    public void clear() { endUnit(); }

    @Override
    public List<Object> backingArrays() { return table.backingArrays(); }
}
