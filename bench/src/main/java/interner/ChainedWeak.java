package interner;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The layout of dotc's `WeakHashSet` (as used by `Uniques`): a power-of-two array of buckets, each a chain of
 * `Entry`s, where an `Entry` is a `WeakReference` to the element that also carries its hash and the next link. A new
 * entry goes at the head of its bucket. Collected entries are unlinked by draining a `ReferenceQueue` before each
 * lookup. The table doubles when it holds more than half as many entries as buckets.
 *
 * <p>The bucket array is one object, so past half a G1 region (2 MB at an 8 GB heap, 512K buckets with compressed
 * oops) it is humongous: allocated directly in the old generation. Every insert then stores a young `Entry` into an
 * old array.
 */
public final class ChainedWeak<K> implements Interner<K> {
    static final class Entry<K> extends WeakReference<K> {
        final int hash;
        Entry<K> next;

        Entry(K elem, int hash, Entry<K> next, ReferenceQueue<? super K> queue) {
            super(elem, queue);
            this.hash = hash;
            this.next = next;
        }
    }

    private final int initialCapacity;
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private Entry<K>[] table;
    private int count;
    private int threshold;

    public ChainedWeak(int initialCapacity) {
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
        removeStaleEntries();
        int bucket = hash & (table.length - 1);
        Entry<K> head = table[bucket];
        for (Entry<K> e = head; e != null; e = e.next) {
            if (e.hash == hash) {
                K x = e.get();
                if (x != null && shape.matches(x, a, b, c)) return x;
            }
        }
        K x = shape.make(hash, a, b, c);
        table[bucket] = new Entry<>(x, hash, head, queue);
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

    @SuppressWarnings("unchecked")
    private void removeStaleEntries() {
        Entry<K> stale;
        while ((stale = (Entry<K>) queue.poll()) != null) {
            int bucket = stale.hash & (table.length - 1);
            Entry<K> prev = null;
            for (Entry<K> e = table[bucket]; e != null; prev = e, e = e.next) {
                if (e == stale) {
                    if (prev == null) table[bucket] = e.next;
                    else prev.next = e.next;
                    count--;
                    break;
                }
            }
        }
    }

    @Override
    public int size() { return count; }

    @Override
    public void clear() {
        init();
        while (queue.poll() != null) {}
    }

    @Override
    public List<Object> backingArrays() { return List.of((Object) table); }
}
