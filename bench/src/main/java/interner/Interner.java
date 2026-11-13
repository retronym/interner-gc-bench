package interner;

import java.util.List;

/** A hash-consing table: one canonical element per equal set of parts. Not thread-safe. */
public interface Interner<K> {
    /** The element with hash `hash` that `shape` matches with parts `a`, `b`, `c`, made and added if absent. */
    K intern(int hash, Shape<K> shape, Object a, Object b, Object c);

    /** The number of elements held (for the weak tables, counting ones collected since the last lookup). */
    int size();

    /** Drop every element. */
    void clear();

    /** Called at the end of each compilation unit. Only {@link Scoped} acts on it. */
    default void endUnit() {}

    /** The table's current backing arrays, for the mechanism tests. */
    List<Object> backingArrays();

    /**
     * The tables by name: chained-weak, chained-strong, chunked-weak, chunked-strong, scoped; and chunked-weak-rejuv, a
     * chunked-weak that copies an element chunk after one collection if it took at least 256 stores since the last one
     * (half its cards), or chunked-weak-rejuv-all, which copies every element chunk after every collection.
     */
    static <K> Interner<K> create(String name, int initialCapacity) {
        return switch (name) {
            case "chained-weak" -> new ChainedWeak<>(initialCapacity);
            case "chained-strong" -> new ChainedStrong<>(initialCapacity);
            case "chunked-weak" -> new ChunkedWeak<>(initialCapacity);
            case "chunked-strong" -> new ChunkedStrong<>(initialCapacity);
            case "scoped" -> new Scoped<>(initialCapacity);
            case "chunked-weak-rejuv" -> new ChunkedWeak<>(initialCapacity, 1, 256);
            case "chunked-weak-rejuv-all" -> new ChunkedWeak<>(initialCapacity, 1, 0);
            case "chunked-boxed" -> new ChunkedBoxed<>(initialCapacity);
            case "chunked-weak-lazy" -> new ChunkedWeakLazy<>(initialCapacity);
            default -> throw new IllegalArgumentException("unknown interner: " + name);
        };
    }

    List<String> NAMES = List.of("chained-weak", "chained-strong", "chunked-weak", "chunked-strong", "scoped", "chunked-weak-rejuv", "chunked-weak-rejuv-all", "chunked-boxed", "chunked-weak-lazy");

    /** Whether a table drops elements nobody else holds, so that it can outlive a compile. */
    static boolean isWeak(String name) { return name.contains("-weak"); }
}
