# Repository agent instructions

This repository follows the shared ORESoftware fleet policy.

<!-- BEGIN ores-agents-pointer: managed by ORESoftware/my-ai; edit there, not here -->

## Canonical agent instructions

Before doing anything else in this repository, also read:

    .ores/agents/AGENTS.md

That path is a symlink to `~/codes/oresoftware/my-ai/AGENTS.md`, whose canonical copy is
<https://github.com/ORESoftware/my-ai/blob/main/AGENTS.md>.

The symlink is deliberately not committed. If it is missing locally, run
`~/codes/oresoftware/my-ai/scripts/link-repo-agents.sh` or fetch the canonical policy above.
A missing local symlink is a setup gap, never permission to skip the policy.

<!-- END ores-agents-pointer -->

## Repository-specific rule

Preserve the ownership boundaries documented in this repository's README. Cross-repository
LiteGraph semantics belong in the canonical interfaces/contracts repositories rather than
being independently redefined here.
