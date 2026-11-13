package interner.workload;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of one simulated compile. The defaults follow the census of dotc's interners on scalaz and dotc at an 8 GB
 * heap; {@link Census} measures what a generated compile actually did. Parsed from `key=value,key=value`.
 */
public final class WorkloadConfig {
    /** Multiplies {@link #lookups} and {@link #units}; everything else is per lookup, so allocation scales too. */
    public double scale = 1.0;
    /** Interner lookups per compile, counting every level of a cascade. */
    public int lookups = 5_000_000;
    /**
     * Scopes per compile: a scope is one (unit, phase) pair, as in the census, so it holds a few hundred lookups. "Unit"
     * below means scope.
     */
    public int units = 8_000;
    /** Target fraction of lookups that find an existing element. */
    public double hitRate = 0.65;
    /** Fraction of picks of an existing node (hit targets and parts of new nodes) that come from earlier units. */
    public double crossUnitShare = 0.5;
    /** Mean distance, in units, of a cross-unit pick (geometric). Small, so cross-unit hits are still young. */
    public double unitDistanceMean = 4;
    /** Of the cross-unit picks, the fraction that land on the "hot" pool made before the first unit (Predef-like). */
    public double hotShare = 0.25;
    /** Nodes in the hot pool. */
    public int hotNodes = 4_000;
    /** Fraction of same-unit picks within 64 inserts of the newest node (sets the geometric reuse distance). */
    public double reuseWithin64 = 0.75;
    /** Fraction of new nodes the unit retains to the end of the compile; the rest die once out of reach. */
    public double retain = 0.75;
    /** Retained "tree" objects (24 bytes each) per retained node, standing in for trees and denotations. */
    public double treesPerNode = 2;
    /** Short-lived garbage per lookup, in bytes. */
    public int garbagePerLookup = 700;
    /** Initial capacity of the interner (dotc: 0x8000 for `Uniques`). */
    public int initialCapacity = 0x8000;
    /** Seed of the generator. */
    public long seed = 42;

    public int scaledLookups() { return (int) Math.max(1, Math.round(lookups * scale)); }

    public int scaledUnits() { return (int) Math.max(1, Math.round(units * scale)); }

    public static WorkloadConfig parse(String spec) {
        WorkloadConfig c = new WorkloadConfig();
        if (spec == null || spec.isBlank()) return c;
        for (String kv : spec.split("[,;]")) {
            if (kv.isBlank()) continue;
            int eq = kv.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("expected key=value: " + kv);
            c.set(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        return c;
    }

    public WorkloadConfig set(String key, String value) {
        try {
            Field f = WorkloadConfig.class.getField(key);
            Class<?> t = f.getType();
            if (t == int.class) f.setInt(this, (int) Double.parseDouble(value.replace("_", "")));
            else if (t == long.class) f.setLong(this, Long.parseLong(value.replace("_", "")));
            else f.setDouble(this, Double.parseDouble(value));
            return this;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("unknown workload parameter: " + key);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String toString() {
        List<String> kvs = new ArrayList<>();
        for (Field f : WorkloadConfig.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try { kvs.add(f.getName() + "=" + f.get(this)); } catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
        return String.join(",", kvs);
    }
}
