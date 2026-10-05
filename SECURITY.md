# Security notes

## RustSec dependency gate

CI runs `cargo audit` against the generated dependency lock and fails on RustSec advisories except for the narrowly tracked exception below.

### RUSTSEC-2023-0071 (`rsa` / Marvin timing attack)

`sqlx` is configured with `default-features = false` and only the Tokio/Rustls, PostgreSQL, and JSON features. Cargo lockfile resolution can nevertheless retain SQLx's optional MySQL dependency packages, including `rsa 0.9.x`, even when they are not compiled into this service.

The advisory currently has no patched `rsa` release. ORES Zen therefore does **not** treat an unverified lockfile ignore as acceptable. CI first runs an inverse dependency-tree check across all enabled application features and fails if `rsa` appears in the active graph. Only after that proof does the advisory scan ignore RUSTSEC-2023-0071 as a lock-only false positive for this Postgres-only binary.

Remove this exception as soon as either:

- SQLx/Cargo no longer places the inactive MySQL/RSA package in the generated lockfile; or
- RustSec marks a patched release and the dependency graph can be updated.

If any future feature change activates MySQL or `rsa`, the active-graph gate must remain red until the dependency is removed or a patched implementation is available.
