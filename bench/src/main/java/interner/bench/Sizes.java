package interner.bench;

import interner.workload.WorkloadConfig;

/** The `size` parameter of the benchmarks (perflab's corpus): a named scale of the default compile. */
final class Sizes {
    private Sizes() {}

    static WorkloadConfig config(String size, String overrides) {
        double scale = switch (size) {
            case "full" -> 1.0;
            case "half" -> 0.5;
            case "quarter" -> 0.25;
            case "tenth" -> 0.1;
            default -> throw new IllegalArgumentException("size: full, half, quarter or tenth, not " + size);
        };
        return WorkloadConfig.parse(overrides).set("scale", Double.toString(scale));
    }
}
