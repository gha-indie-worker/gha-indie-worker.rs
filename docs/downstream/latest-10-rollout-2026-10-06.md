# Latest-10 downstream rollout — 2026-10-06

This tracks the org-wide compatibility sweep for source PRs #405, #406, #408, #409, #410, #411, #412, #414, #415 and #416.

The ten PRs are intentionally **not** represented as one compiler SHA: the select chain, binding/channel work, SelectPlan work, and actor/code-image stack are not all one linear head yet. Downstream repositories record the intended contracts now and should advance compiler pins only after the relevant upstream stacks are integrated and exact-head CI is green.

## Downstream PRs

- oreslang-cli: https://github.com/ores-truffle-oreslang/oreslang-cli/pull/5
- oreslang-rpc: https://github.com/ores-truffle-oreslang/oreslang-rpc/pull/3
- rx-ores: https://github.com/ores-truffle-oreslang/rx-ores/pull/6
- oreslang-demo: https://github.com/ores-truffle-oreslang/oreslang-demo/pull/18
- oreslang-ssr: https://github.com/ores-truffle-oreslang/oreslang-ssr/pull/3
- oreslang-format: https://github.com/ores-truffle-oreslang/oreslang-format/pull/18
- oreslang-demos: https://github.com/ores-truffle-oreslang/oreslang-demos/pull/3
- oreslang-hpc-finance: https://github.com/ores-truffle-oreslang/oreslang-hpc-finance/pull/3
- oreslang-data-science: https://github.com/ores-truffle-oreslang/oreslang-data-science/pull/5
- oreslang-http-routing: https://github.com/ores-truffle-oreslang/oreslang-http-routing/pull/6
- rx-ores-callbacks: https://github.com/ores-truffle-oreslang/rx-ores-callbacks/pull/5
- oreslang-design-patterns: https://github.com/ores-truffle-oreslang/oreslang-design-patterns/pull/3
- rx-oreslang-channels: https://github.com/ores-truffle-oreslang/rx-oreslang-channels/pull/6
- oreslang-serialization-and-validation: https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation/pull/5

## Cross-repo contract

1. `do select` and `do nb select` are no-result forms; arm returns are local/discarded.
2. Traditional select remains valid; SelectPlan is an additive optimization target.
3. #411 binding capability distinctions must be preserved rather than normalized away.
4. Task-local data-only Channel<T> admission does not imply actor-mailbox channel/function transport.
5. Actor mailboxes remain serialized/capability boundaries.
6. #415 actor declarations are groundwork; source-level `spawn Worker()` lifecycle remains follow-up.
7. #416 shares immutable checked code data only inside one OS process, not cross-process/JIT machine code.

## CI policy

When source-org Actions cannot allocate a runner, mirror the exact source Git tree/blob SHAs into a funded test-org branch and run Actions there. Do not rely on private cross-org checkout with the test repo's GITHUB_TOKEN and do not add secrets merely to make the mirror work.
