package interner.workload;

import interner.Interner;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * One simulated compile: a sequence of units, each of which interns types built from parts and allocates short-lived
 * garbage alongside, against a fresh interner.
 *
 * <p>Each step either re-interns an existing type, as a compiler does when it builds a type from parts it already has
 * (a hit at every level of the type's spine), or builds a new one from existing parts and fresh symbols (misses, with
 * the occasional accidental hit on a shared suffix). Existing nodes are picked mostly from the most recent inserts
 * (geometric reuse distance), and otherwise from earlier units (geometric unit distance) or a hot pool made before the
 * first unit. Every hit target is still reachable, so weak and strong tables see the same lookups.
 *
 * <p>Each new node goes into a ring of the last {@value #RING} inserts. Most are also retained by their unit, together
 * with a few "tree" objects that refer to them, until the compile ends; the rest die once they leave the ring and no
 * other node refers to them.
 */
public final class Compile {
    static final int RING = 4096;

    /** A retained object that refers to an interned node, standing in for trees and denotations. */
    static final class Tree {
        final Node tpe;
        final Tree next;

        Tree(Node tpe, Tree next) {
            this.tpe = tpe;
            this.next = next;
        }
    }

    private final WorkloadConfig cfg;
    private final Interner<Node> table;
    private final Census census;
    private final SplittableRandom rng;
    /** Retention decisions, apart from `rng`, so that tables that miss more (Scoped) still see the same steps. */
    private final SplittableRandom retainRng;
    private final Node.Kind[] kinds = Node.kinds();
    private final double logSame, logUnit;
    private final int garbageWords;

    private final Node[] ring = new Node[RING];
    private final int[] ringUnit = new int[RING];
    private int inserted;

    private Node[] hot;
    private final Node[][] roots;
    private final Tree[] trees;
    private final int[] insertedAtUnitEnd;
    private Node[] cur = new Node[1024];
    private int curN;
    private Tree curTrees;
    private int unit = -1;

    private final Object[] scratch = new Object[32];
    private int scratchPos;

    long lookups, hits, hitStepLookups;
    private boolean inHitStep;
    private int pickedUnit, pickedDistance;

    public Compile(WorkloadConfig cfg, Interner<Node> table, long seed, Census census) {
        this(cfg, table, seed, census, null);
    }

    /**
     * A compile that shares `hot` (the hot pool of an earlier compile against the same `table`) instead of making its
     * own, as successive compiles in a resident compiler share the classpath's types. With `hot` null it makes one.
     */
    public Compile(WorkloadConfig cfg, Interner<Node> table, long seed, Census census, Node[] hot) {
        this.hot = hot;
        this.cfg = cfg;
        this.table = table;
        this.census = census;
        this.rng = new SplittableRandom(seed);
        this.retainRng = rng.split();
        double pSame = 1 - Math.pow(1 - cfg.reuseWithin64, 1.0 / 64);
        this.logSame = Math.log(1 - pSame);
        this.logUnit = Math.log(1 - 1 / Math.max(1.0, cfg.unitDistanceMean));
        this.garbageWords = Math.max(0, (cfg.garbagePerLookup - 16) / 8);
        int units = cfg.scaledUnits();
        this.roots = new Node[units][];
        this.trees = new Tree[units];
        this.insertedAtUnitEnd = new int[units];
    }

    /** Run the compile; returns a checksum of the nodes it interned. */
    public long run() {
        int units = cfg.scaledUnits();
        long budget = cfg.scaledLookups();
        if (hot == null) {
            hot = new Node[cfg.hotNodes];
            for (int i = 0; i < hot.length; i++) hot[i] = lookup(Node.REF, null, freshSym(), null);
        }
        if (census != null) census.compileStart(units);
        long checksum = 0;
        for (unit = 0; unit < units; unit++) {
            if (census != null) census.unitStart(unit);
            long unitEnd = budget * (unit + 1) / units;
            while (lookups < unitEnd) checksum += step();
            roots[unit] = Arrays.copyOf(cur, curN);
            trees[unit] = curTrees;
            insertedAtUnitEnd[unit] = inserted;
            curN = 0;
            curTrees = null;
            table.endUnit();
        }
        if (census != null) census.compileEnd(this);
        return checksum;
    }

    private int step() {
        // steer by the lookups of hit steps, not by actual hits, so the sequence of steps does not depend on the table
        double realized = lookups == 0 ? cfg.hitRate : (double) hitStepLookups / lookups;
        double p = Math.min(1, Math.max(0, cfg.hitRate + 2 * (cfg.hitRate - realized)));
        if (rng.nextDouble() < p) {
            inHitStep = true;
            int h = hitStep();
            inHitStep = false;
            return h;
        }
        return missStep();
    }

    /** Re-intern an existing node from its parts, spine first. */
    private int hitStep() {
        Node t = pick();
        if (census != null) census.hitStep(unit, pickedUnit, pickedDistance);
        Node n = switch (t.kind) {
            case Node.LIST -> relist(t);
            case Node.APPLIED -> {
                Node args = relist((Node) t.b);
                yield lookup(Node.APPLIED, t.a, args, null);
            }
            default -> lookup(t.kind, t.a, t.b, t.c);
        };
        return n.hash;
    }

    private Node relist(Node l) {
        if (l == null) return null;
        Node tail = relist((Node) l.b);
        return lookup(Node.LIST, l.a, tail, null);
    }

    /** Make a new node from existing parts and, for refs, a fresh symbol. */
    private int missStep() {
        double r = rng.nextDouble();
        Node n;
        if (r < 0.4) {
            Object prefix = rng.nextDouble() < 0.3 ? null : pick();
            n = lookup(Node.REF, prefix, freshSym(), null);
        } else if (r < 0.8) {
            Node tycon = pick();
            int len = 1 + rng.nextInt(3);
            Node args = null;
            for (int i = 0; i < len; i++) args = lookup(Node.LIST, pick(), args, null);
            n = lookup(Node.APPLIED, tycon, args, null);
        } else {
            n = lookup(Node.REFINED, pick(), freshSym(), pick());
        }
        return n.hash;
    }

    private Node lookup(byte kind, Object a, Object b, Object c) {
        Node.Kind k = kinds[kind];
        long made = k.made;
        Node n = table.intern(Node.hash(kind, a, b, c), k, a, b, c);
        lookups++;
        if (inHitStep) hitStepLookups++;
        if (k.made == made) hits++;
        // every node a miss step builds counts as recent, hit or not, so the steps do not depend on whether the
        // table still held an equal node (weak tables drop dead ones)
        if (!inHitStep) onBuilt(n);
        if (garbageWords > 0) scratch[scratchPos++ & (scratch.length - 1)] = new long[garbageWords];
        return n;
    }

    private void onBuilt(Node n) {
        int slot = inserted & (RING - 1);
        ring[slot] = n;
        ringUnit[slot] = unit;
        inserted++;
        if (unit >= 0 && retainRng.nextDouble() < cfg.retain) {
            if (curN == cur.length) cur = Arrays.copyOf(cur, curN * 2);
            cur[curN++] = n;
            double t = cfg.treesPerNode;
            int whole = (int) t;
            int count = whole + (retainRng.nextDouble() < t - whole ? 1 : 0);
            for (int i = 0; i < count; i++) curTrees = new Tree(n, curTrees);
        }
    }

    private Sym freshSym() { return new Sym(rng.nextInt()); }

    private int geometric(double log1mp) {
        return (int) (Math.log(1 - rng.nextDouble()) / log1mp);
    }

    /** An existing, reachable node. Sets `pickedUnit` (-1 for the hot pool) and `pickedDistance` (in inserts). */
    private Node pick() {
        if (rng.nextDouble() < cfg.crossUnitShare || inserted == 0) {
            if (unit <= 0 || rng.nextDouble() < cfg.hotShare) return pickHot();
            int d = Math.min(unit, 1 + geometric(logUnit));
            for (int u = unit - d; u >= 0; u--) {
                Node[] rs = roots[u];
                if (rs.length > 0) {
                    pickedUnit = u;
                    pickedDistance = inserted - insertedAtUnitEnd[u] + 1;
                    return rs[rng.nextInt(rs.length)];
                }
            }
            return pickHot();
        }
        int d = Math.min(Math.min(inserted, RING), 1 + geometric(logSame));
        int slot = (inserted - d) & (RING - 1);
        pickedUnit = ringUnit[slot];
        pickedDistance = d;
        return ring[slot];
    }

    private Node pickHot() {
        pickedUnit = -1;
        pickedDistance = inserted;
        return hot[rng.nextInt(hot.length)];
    }

    /** Nodes built by miss steps (and the hot pool), whether or not the table already held them. */
    int inserted() { return inserted; }

    /** The hot pool, to share with the next compile against the same table. */
    public Node[] hot() { return hot; }

    /** Nodes the table made, i.e. misses. */
    public long made() {
        long n = 0;
        for (Node.Kind k : kinds) n += k.made;
        return n;
    }

    /** Nodes retained to the end of the compile (excluding the hot pool and the ring). */
    long retainedNodes() {
        long n = 0;
        for (Node[] rs : roots) if (rs != null) n += rs.length;
        return n;
    }

    public long lookups() { return lookups; }

    public long hits() { return hits; }

    /** Keeps the compile's retained state reachable until this call, for live-heap measurement. */
    public int touch() { return Arrays.hashCode(trees) ^ hot.length ^ ring.length; }
}
