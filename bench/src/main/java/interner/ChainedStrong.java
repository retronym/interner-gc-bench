package interner;

import java.util.List;

/** {@link ChainedWeak} with strong entries: the same bucket array and chains, no `ReferenceQueue`. */
public final class ChainedStrong<K> implements Interner<K> {
    static final class Entry<K> {
        final K elem;
        final int hash;
        Entry<K> next;

        Entry(K elem, int hash, Entry<K> next) {
            this.elem = elem;
            this.hash = hash;
            this.next = next;
        }
    }

    private final int initialCapacity;
    private Entry<K>[] table;
    private int count;
    private int threshold;

    public ChainedStrong(int initialCapacity) {
        this.initialCapacity = Tables.powerOfTwoAtLeast(initialCapacity);
        init();
    }

    @SuppressWarnings("unchecked")
    private void init() {
        table = (Entry<K>[]) new Entry<?>[initialCapacity];
        threshold = table.length / 2;
        count = 0;
    }

    @Override
    public K intern(int hash, Shape<K> shape, Object a, Object b, Object c) {
        int bucket = hash & (table.length - 1);
        Entry<K> head = table[bucket];
        for (Entry<K> e = head; e != null; e = e.next) {
            if (e.hash == hash && shape.matches(e.elem, a, b, c)) return e.elem;
        }
        K x = shape.make(hash, a, b, c);
        table[bucket] = new Entry<>(x, hash, head);
        if (++count > threshold) resize();
        return x;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<K>[] old = table;
        table = (Entry<K>[]) new Entry<?>[old.length * 2];
        threshold = table.length / 2;
        int mask = table.length - 1;
        for (Entry<K> e : old) {
            while (e != null) {
                Entry<K> next = e.next;
                int bucket = e.hash & mask;
                e.next = table[bucket];
                table[bucket] = e;
                e = next;
            }
        }
    }

    @Override
    public int size() { return count; }

    @Override
    public void clear() { init(); }

    @Override
    public List<Object> backingArrays() { return List.of(table); }
}
