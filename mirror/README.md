# ores-cli

`ores-cli` is a Rust command-line application **and reusable Rust SDK** for portfolio-wide repository governance. It installs both the long-form `ores-cli` executable and the shorter `oresc` alias. It audits GitHub topology through the authenticated GitHub CLI, checks local repository structure, verifies Cargo/zed-pkg publication metadata, audits SOPS-encrypted environment requirements without exposing values, and delegates TypeSpec ↔ JSON Schema parity to the canonical ORESoftware validator.

## Output contract

Normal results and audit findings are written to **stdout**.

- JSON is enabled by default for report-producing commands. Each line is one `next-loggers/v1` record produced with the Rust SDK from `ores-otel`.
- Use `--no-json` to select compact plain text. `flags-2-env` supplies this canonical boolean-negation form.
- `org list-missing-repos` is intentionally a selector: it emits one repository name per line so its output can be passed directly to another command. Add `--report` to use the normal JSON/plain report renderer instead.
- Exit `0` means the command passed.
- Exit `2` means findings stopped evaluation.
- Stderr is reserved for invalid invocations and failures of `ores-cli` or a required runtime dependency. Usage failures exit `64`; runtime failures exit `70`.

This makes pipelines safe and unsurprising:

```bash
ores-cli audit repo --path . --profile rust-cli >audit.jsonl
status=$?

case "$status" in
  0) echo "ready" ;;
  2) echo "policy findings are in audit.jsonl" ;;
  *) echo "ores-cli failed" >&2 ;;
esac
```

Plain output is explicit:

```bash
ores-cli --no-json audit package --path .
```

## Flag authority

`.cli-flags.toml` is the only public command/flag authority. At startup, the executable:

1. audits the contract with the bundled `flags-2-env` Rust binding;
2. parses and resolves the command path with that binding;
3. rejects unknown or invalid typed values; and
4. coerces the resulting environment map into Rust command options.

There is no Clap, ad hoc argv loop, or second parser. Positional repository selectors are read from the structured `flags-2-env` extras channel.

JSON defaults on through `ORES_CLI_JSON=true`. It can be disabled by environment or canonical CLI negation:

```bash
ORES_CLI_JSON=false ores-cli doctor
ores-cli --no-json doctor
```

## Installation

### Clone and post-install (recommended)

Authenticate with GitHub, clone the private repository, and run the repository-owned installer:

```bash
gh auth login
gh auth status --active --hostname github.com

gh repo clone ORESoftware/ores-cli
cd ores-cli
./post-install.sh
```

The installer:

- installs the Rust toolchain pinned by `rust-toolchain.toml` when necessary;
- builds committed `HEAD` in an isolated temporary checkout, leaving the clone clean;
- installs both `ores-cli` and `oresc` under `${CARGO_INSTALL_ROOT:-${CARGO_HOME:-$HOME/.cargo}}` by default;
- retains the package-owned `.cli-flags.toml` under `share/ores-cli`;
- writes small launchers that set `FLAGS2ENV_CONFIG`, so the commands work outside the clone;
- prints the revision, tool versions, install paths, doctor report, and useful next commands; and
- never creates repositories as part of installation.

Install and immediately inspect an organization:

```bash
./post-install.sh \
  --org sonus-auris \
  --family-prefix sonus-auris
```

For an organization whose repository prefix differs from its login, pass the explicit prefix:

```bash
./post-install.sh \
  --org hhaus-org \
  --family-prefix hhaus
```

Preview the full plan without installing or changing files:

```bash
./post-install.sh --dry-run --org sonus-auris
```

Choose another installation prefix when needed:

```bash
./post-install.sh --install-root "$HOME/.local"
export PATH="$HOME/.local/bin:$PATH"
```

The script installs committed `HEAD`; it warns and excludes tracked working-tree changes. Run `./post-install.sh --help` for every option and environment override.

### zed-pkg

The repository is an installable Zed CLI package. Its `.zpkg.toml` exports both commands and explicitly binds them to the package-owned `.cli-flags.toml`:

```toml
[bin]
ores-cli = "target/release/ores-cli"
oresc = "target/release/oresc"

[interop.flags-2-env]
config = ".cli-flags.toml"
bins = ["ores-cli", "oresc"]
```

After the package is published, install and run it with:

```bash
zed install oresoftware/ores-cli@^0.1 --allow-build --adapter none
zed run oresc -- doctor
zed run ores-cli -- --no-json doctor
```

Zed retains `.cli-flags.toml` with the built binaries. The explicit interop declaration prevents a consumer working directory from substituting a different flags contract.

### Direct source execution

During development, run either binary from the clone so the repository-owned contract is available:

```bash
cargo run --bin oresc -- doctor
cargo run --bin ores-cli -- --no-json doctor
```

For a global clone-based installation, use `./post-install.sh` rather than a bare `cargo install`; the post-install path retains and binds `.cli-flags.toml` instead of depending on the caller's current directory.

## Usage

### Diagnose the runtime

```bash
oresc doctor
```

The report records flags-2-env, `ores-otel`, `ores-middleware`, the local rate-limit integration, and the availability of `gh`, `tjsv`, and `sops`.

### Inspect and manage a GitHub organization

Authentication is owned by the GitHub CLI:

```bash
gh auth login
gh auth status --active --hostname github.com
```

`oresc` never accepts a GitHub token flag and never puts credentials on argv.

Inspect an organization, its visible repository inventory, its missing canonical-repository count, and the actions available against it:

```bash
oresc org --name <gh-org>
```

List missing canonical repositories as one shell-safe name per line:

```bash
oresc org --name <gh-org> list-missing-repos
```

Create every currently missing canonical repository:

```bash
oresc org --name <gh-org> create-missing-repos --all
```

The selector output can be passed back into either the listing command or the creation command:

```bash
oresc org --name <gh-org> list-missing-repos \
  $(oresc org --name <gh-org> list-missing-repos)
oresc org --name <gh-org> create-missing-repos \
  $(oresc org --name <gh-org> list-missing-repos)
```

The canonical set contains `.github` plus the standard repository family prefixed by the organization name. Override the prefix with `--family-prefix`, narrow the family with `--family-members`, add exact names with `--expected-repos`, or disable the inferred family with `--no-standard-family`.

Creation is explicit: callers must provide `--all` or repository-name positionals. New repositories default to `private`, are initialized so they receive a first commit, and can instead use `--visibility public` or `--visibility internal`.

Clone or update the complete local checkout of an organization under ~/codes/<org>:

```bash
# clone repositories that are missing locally
oresc org --name oresoftware clone --all

# fast-forward pull repositories already present locally
oresc org --name oresoftware pull --all

# clone missing repositories and pull existing repositories
oresc org --name oresoftware sync --all
```

The workspace root defaults to ~/codes, and the organization login is appended automatically. Override the root with --dir:

```bash
oresc org --name ores-truffle-oreslang sync --all --dir "$HOME/codes"
```

Before authentication, inventory, directory creation, cloning, or pulling, interactive runs print the resolved final path (for example /Users/alex/codes/ores-truffle-oreslang) and require an exact YES confirmation. Automation can bypass only that prompt with an explicit --non-interactive flag:

```bash
oresc org --name ores-truffle-oreslang sync --all --non-interactive
```

The safety bypass and --all must be present on argv; environment values cannot silently authorize them. Existing checkout directories are verified to be Git repositories whose origin points at the expected github.com/<org>/<repo>. Symbolic links, non-Git collisions, and origin mismatches are left untouched and reported. Pull uses git pull --ff-only, so it does not create merge commits. The pull command never clones missing repositories; sync does both.

Change visibility on existing repositories with a separate fail-stop command. Preview the exact frozen target set first:

```bash
oresc org --name canonical-cloud set-repo-visibility \
  --all \
  --visibility private \
  --exclude canonical-cloud.github.io \
  --dry-run
```

Apply the same plan by removing `--dry-run`:

```bash
oresc org --name canonical-cloud set-repo-visibility \
  --all \
  --visibility private \
  --exclude canonical-cloud.github.io \
  --accept-visibility-change-consequences
```

Every `--exclude` name must exist in the authoritative preflight inventory, so a misspelled public exception stops before any mutation. A real write requires the destructive scope and authorization on argv: the organization (`--name`/alias), `--all` when used, any nonempty `--exclude`, `--visibility ...`, and `--accept-visibility-change-consequences`. Environment/default values cannot select, broaden, exclude from, authorize, or redirect destructive apply. Use `--dry-run` first to inspect the exact per-repository `from` → `to` plan. Whole-organization `--all` requires an active organization-owner membership so the inventory is authoritative; explicit repository selections instead require repository-admin permission only for repositories that actually need a change. Preflight reports and blocks repositories that the viewer cannot administer and forks that would need a visibility change, because fork visibility is controlled by the repository network rather than the fork alone. The command never expands its target set after preflight, pins the active GitHub account across the operation, stops on the first uncertain write, validates mutation receipts, and verifies the entire selected scope plus exclusions with a fresh inventory after mutation.

To receive the normal structured report rather than selector lines:

```bash
oresc org --name <gh-org> list-missing-repos --report
```

### Audit a GitHub organization or account

The deeper audit command remains available and backward compatible:

```bash
oresc audit org \
  --org sonus-auris \
  --family-prefix sonus-auris
```

The command uses `gh repo list` for inventory and `gh api` for optional top-level layout checks. It reports:

- missing explicit repositories, including `.github` by default;
- missing members of the standard repository family when `--family-prefix` is set;
- a missing active `docs` or `*-docs` repository;
- archived required repositories;
- inventory truncation; and
- missing root entries such as `README.md`, `LICENSE`, `AGENTS.md`, `.github`, and `.zpkg.toml`.

The standard family suffixes are configurable with `--family-members`. Additional exact names can be supplied as comma-separated values:

```bash
oresc audit org \
  --org example-org \
  --expected-repos '.github,example-assets,example-e2e' \
  --family-prefix example
```

Disable the potentially broader API walk while retaining topology checks:

```bash
oresc audit org --org example-org --no-check-layout
```

### Audit a local repository

```bash
oresc audit repo --path . --profile rust-cli
```

Profiles:

| Profile | Required shape |
| --- | --- |
| `baseline` | `README.md`, `LICENSE`, `AGENTS.md`, `.github` |
| `rust-cli` | baseline plus `.cli-flags.toml`, `.zpkg.toml`, `.zpkg.lock`, Cargo metadata, `src/lib.rs`, and `src/main.rs` |
| `docs` | baseline plus `docs/` |
| `interfaces` | baseline plus `contracts/`, `schema/`, and `typespec/` |

The audit also validates any discovered `.cli-flags.toml`, requires `generated/README.md` for generated directories, and flags incomplete common TypeSpec/JSON Schema source pairs.

### Audit encrypted environment requirements

```bash
oresc audit env --path .
```

The default is deliberately least-privileged and audits only `env/enc/dev.env.enc`. Select another authorized environment explicitly:

```bash
oresc audit env --environment stage
oresc audit env --environment prod
oresc audit env --environments dev,stage
oresc audit env --environments all
```

The command decrypts selected SOPS dotenv ciphertext **in memory**, retains only variable names, and verifies those names exist in the current process environment. It never writes `.env` or `env/dec/*`, never emits decrypted values, and discards SOPS decrypt stderr at the subprocess boundary. Empty local values fail by default; use `--no-require-non-empty` for presence-only checks.

It also fails closed on malformed or duplicate dotenv declarations, noncanonical ciphertext aliases such as `qa.env.enc`, symlinked encrypted paths, repository-escape attempts through `--encrypted-dir`, oversized ciphertext inputs, and undecryptable selected files. See [`docs/encrypted-env-audit.md`](docs/encrypted-env-audit.md) for the full security and `ores-sops` boundary.

### Audit Cargo and zed-pkg synchronization

```bash
oresc audit package --path .
```

The command compares:

- Cargo package name, version, description, license, and repository metadata against `.zpkg.toml`;
- declared Rust build outputs against Cargo binary targets;
- both `ores-cli` and `oresc` installation targets;
- `.zpkg.lock` presence and validity;
- package publish exclusions for secrets, generated audit reports, local vendor trees, build artifacts, and logs; and
- package smoke-test coverage for both installed binaries.

### Audit TypeSpec / JSON Schema parity

```bash
oresc audit contract \
  --typespec typespec/main.tsp \
  --schema schema/main.schema.json
```

This delegates to `tjsv`, the canonical `ORESoftware/typespec-json-schema-validator` executable. The command invokes one executable directly rather than a shell fragment, captures bounded output, writes the requested report, and preserves the fail-closed parity result in the normal `ores-cli` report model.

## Development verification

```bash
cargo fmt --all --check
cargo clippy --all-targets --all-features --locked -- -D warnings
cargo test --all-targets --all-features --locked
```

CI additionally runs the clone-based post-install regression and both JSON/plain output-contract checks before exporting the committed `Cargo.lock` artifact.
