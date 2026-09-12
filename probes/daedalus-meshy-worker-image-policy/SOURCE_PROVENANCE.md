# DEN-2506 Meshy worker image publication policy probe

This public fixture validates operational policy only. It contains no Daedalus business logic, credentials, provider requests, Cloudflare account details, R2 object data, or live deployment access.

Source represented:

- repository: `daedalus-fab/fabrication-server.rs`
- pull request: `#9`
- source head: `38995918fe05aaaa526845bef3fe94d8ad9adb98`
- workflow blob: `0ebd2a53482640c9ee5d228f92aad47bbdec135e`
- policy blob: `2a963a4f07434a177bc057f60a1e7d31a0e3900a`
- Dockerfile blob: `dc3735a023a0be509e587d98170712852365741f`
- worker manifest blob: `a288ecd371a01d266a799109ca5d7a5e5cc4fbef`

The workflow and policy are copied byte-for-byte and checked with `git hash-object`. The compact resolver files here are explicit placeholders; the sibling lockgraph probe independently validates the real dependency graph and resolver artifact.
