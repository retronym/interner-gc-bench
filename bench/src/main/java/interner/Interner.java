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

    /** The tables by name: chained-weak, chained-strong, chunked-weak, chunked-strong, scoped. */
    static <K> Interner<K> create(String name, int initialCapacity) {
        return switch (name) {
            case "chained-weak" -> new ChainedWeak<>(initialCapacity);
            case "chained-strong" -> new ChainedStrong<>(initialCapacity);
            case "chunked-weak" -> new ChunkedWeak<>(initialCapacity);
            case "chunked-strong" -> new ChunkedStrong<>(initialCapacity);
            case "scoped" -> new Scoped<>(initialCapacity);
            default -> throw new IllegalArgumentException("unknown interner: " + name);
        };
    }

    List<String> NAMES = List.of("chained-weak", "chained-strong", "chunked-weak", "chunked-strong", "scoped");
}
