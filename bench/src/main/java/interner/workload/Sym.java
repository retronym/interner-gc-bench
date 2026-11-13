package interner.workload;

/** A leaf part of a node: a symbol or a name, with a hash drawn from the workload's seeded generator. */
public final class Sym {
    public final int hash;

    public Sym(int hash) { this.hash = hash; }
}
