package interner.workload;

import interner.Shape;

/**
 * An interned "type": a kind and up to three parts, which are other nodes, {@link Sym}s or null. Two nodes are the
 * same type when their kinds are equal and their parts are identical (`eq`), so interning cascades: a type built from
 * interned parts can itself be interned by identity of those parts.
 */
public final class Node {
    public static final byte REF = 0, LIST = 1, APPLIED = 2, REFINED = 3;

    public final int hash;
    public final byte kind;
    public final Object a, b, c;

    Node(int hash, byte kind, Object a, Object b, Object c) {
        this.hash = hash;
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.c = c;
    }

    /** The hash of a node of `kind` with parts `a`, `b`, `c`, from the parts' own hashes (deterministic). */
    public static int hash(byte kind, Object a, Object b, Object c) {
        int h = kind * 0x9E3779B9;
        h = mix(h ^ hashOf(a));
        h = mix(h ^ hashOf(b));
        h = mix(h ^ hashOf(c));
        return h;
    }

    static int hashOf(Object part) {
        if (part == null) return 0x1234567;
        if (part instanceof Node n) return n.hash;
        return ((Sym) part).hash;
    }

    private static int mix(int h) {
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        return h ^ (h >>> 16);
    }

    /** The {@link Shape} of one kind of node. One instance per kind, so a call through `Shape` is monomorphic. */
    public static final class Kind implements Shape<Node> {
        public final byte kind;

        private Kind(byte kind) { this.kind = kind; }

        @Override
        public boolean matches(Node n, Object a, Object b, Object c) {
            return n.kind == kind && n.a == a && n.b == b && n.c == c;
        }

        @Override
        public Node make(int hash, Object a, Object b, Object c) { return new Node(hash, kind, a, b, c); }
    }

    public static final Kind[] KINDS = {new Kind(REF), new Kind(LIST), new Kind(APPLIED), new Kind(REFINED)};
}
