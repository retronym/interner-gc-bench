package interner;

final class Tables {
    private Tables() {}

    /** Slots per chunk of the chunked tables: 64K, so an `int[]` or a compressed-oop `Object[]` chunk is 256 KB. */
    static final int CHUNK_BITS = 16;
    static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    static final int CHUNK_MASK = CHUNK_SIZE - 1;

    static int powerOfTwoAtLeast(int n) {
        int c = 1;
        while (c < n) c <<= 1;
        return c;
    }

    static int chunks(int capacity) { return (capacity + CHUNK_SIZE - 1) >>> CHUNK_BITS; }

    static int chunkLength(int capacity) { return Math.min(capacity, CHUNK_SIZE); }

    static int[][] newHashes(int capacity) {
        int[][] hs = new int[chunks(capacity)][];
        for (int i = 0; i < hs.length; i++) hs[i] = new int[chunkLength(capacity)];
        return hs;
    }

    static Object[][] newElems(int capacity) {
        Object[][] es = new Object[chunks(capacity)][];
        for (int i = 0; i < es.length; i++) es[i] = new Object[chunkLength(capacity)];
        return es;
    }
}
