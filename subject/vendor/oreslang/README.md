# Vendored Oreslang testing framework

`testing.ores` is copied from:

- repository: `ores-truffle-oreslang/oreslang-source.java`
- core revision: `02ca2b2ca141f4570962c3911be5f0c8fd50c6bc`
- source blob: `5eee1995222165c579e4a75d403911e71dcff819`
- source path: `stdlib/testing.ores`

It is vendored so this repository's native tests are reproducible without a
cross-private-repository token.

The vendored file is based on the pinned core blob above, with two explicit
local migrations applied:

- the hardened for-of declaration form (`for const ... of ...`);
- pointer-free reference syntax: ordinary object parameters/calls do not use
  `&` or unary dereference `*`.

These deltas keep the vendored tests aligned with the current Oreslang language
direction. Do not claim the vendored file is byte-for-byte identical to the
pinned blob; audit any additional local delta explicitly.
