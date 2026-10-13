# Managed cyclic graphs with generational handles (Java runtime)

The Java Oreslang implementation can represent cyclic graph relationships
without circular *strong ownership*: `GenerationalGraphArena<T>` owns all node
values, and edges are non-owning (slot, generation) links. The single arena
is the lifetime root. `NodeId` is opaque and tied to one arena identity.
Removing or clearing nodes bumps generations; stale links cannot resolve to
recycled slots. The structure never recursively walks a cycle for teardown.

## Host-side example (Java, not Oreslang guest syntax)

```java
try (var graph = new GenerationalGraphArena<String>(8, 4)) {
    var parent = graph.add("parent");
    var child = graph.add("child");
    graph.connect(parent, child);
    graph.connect(child, parent);
    graph.remove(parent);
    assert graph.resolve(parent).isEmpty();
    assert graph.neighbors(child).isEmpty(); // stale weak-like edge
}
```

## Lifetime / safety contract

- `GenerationalGraphArena` owns **slot references**, not the lifetime of
  external references to the same Java values. A caller holding `T` can
  keep an object alive after removal/close, as ordinary JVM GC semantics.
- `NodeId` is a generational identity, *not* a borrow or address pin.
  It does not strongly retain the whole arena; node resolution checks
  identity+generation while the arena still exists.
- Concurrent Java callers are serialized through synchronized operations.
  This is **not** an off-heap allocator or allocation-free HFT hot path.
- `close` drops all retained node values and adjacency roots without
  recursively tracing graph edges. Self-cycles, reciprocal edges and arbitrary
  cycles are valid. Cycles *inside payload objects* still follow JVM GC.
- `connect` prunes invalidated source edges before quota checking, avoiding
  stale tombstones exhausting a bounded adjacency list.
- `add`, `connect`, `remove` and reads guard foreign/stale IDs;
  exhaustion fails rather than silently growing the arena.
- Values should remain actor-local unless they are independently validated as
  safe to transport. This host helper is not a guest ActorRef or shared-region
  capability. No change to actor mailbox or recursive-value freeze checks.

## Related work

- [PR #468](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/468)
  separately develops actor-confined `Ref<T>`/`WeakRef<T>` and is stacked
  on recursive-type work. That provides identity-bearing owned cells; this
  PR supplies a separately mergeable *index-owned* graph utility.
- [LLVM issue #1](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/1)
  tracks a native address-stable, pinned-region implementation.
- [HPC RFC PR #470](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/470)
  remains a separate design and is not merged by this change.

## Tests / acceptance

`mvn -q -Dtest=GenerationalGraphArenaTest test` exercises self and parent/child
cycles, deterministic close, ABA after remove/reuse/clear, multi-arena
isolation, edge-slot reuse, hard limits and negative operations.

An actual source-language arena type, native layout and actor-affinity
enforcement are future work and **not** claimed here.
