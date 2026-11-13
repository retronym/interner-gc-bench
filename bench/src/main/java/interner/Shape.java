package interner;

/**
 * How an interner tests and builds one kind of element from its parts. A lookup passes the parts rather than a
 * candidate element, so a hit allocates nothing, as dotc's `unique` calls compare `prefix eq`, `tycon eq` and
 * `args.eqElements` without building the type first.
 */
public interface Shape<K> {
    /** Whether `elem` is the element with parts `a`, `b`, `c`. */
    boolean matches(K elem, Object a, Object b, Object c);

    /** A new element with parts `a`, `b`, `c` and hash `hash`, made on a miss. */
    K make(int hash, Object a, Object b, Object c);
}
