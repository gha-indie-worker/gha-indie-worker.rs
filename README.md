# oreslang-format

The canonical formatter for Oreslang source code.

There is deliberately **one format and no style configuration**. The same Rust
library powers the CLI, so editor integrations, CI, and local development use
identical behavior.

## Canonical style

- two spaces per indentation level, never tabs for indentation;
- static `select`, `nb select`, and `try select` arms are indented one level;
- every `case` and `default` arm has a braced body;
- LF line endings, no trailing whitespace, one final newline;
- at most one ordinary blank line;
- **two blank lines between sibling executable function/routine/method declarations**;
- executable declarations and method implementations use the slim arrow `->`;
- interface/trait callable signatures use the type-level fat arrow `=>`;
- conditionals canonically use `if ...; then` / `elif ...; then` / `else` / `fi`;
- class headers keep `as` after the complete inheritance/conformance clause:

```ores
define class User extends Entity implements Named, Serializable as
  pub val String name;

  pub render() -> String {
    return self.name;
  }
end
```

The nesting engine understands `module`, `class`, `interface`, `trait`,
`struct`, actor/braced bodies, `end`, `if`/`fi`, and `do`/`done`. In particular,
`implements Foo, Bar` never creates formatter nesting; the class body begins
only after the class header and is closed by its matching `end`.

Conditional compatibility spellings are migrated automatically: deprecated
`if ... do` becomes `if ...; then`, `elseif` becomes `elif`, and a
single-`fi` `else if` branch becomes `elif`. Loop `do ... done` syntax is
unchanged; the deprecation applies only to using `do` as an if/branch
introducer.

Static select arms use the same nesting rules as other blocks:

```ores
nb select {
  case readch inbox: val value {
    nb writech replies, value * 10;
  }
  default: {
  }
}
```

The formatter adds braces to legacy unbraced select arms, including read arms
without a binding and write/default arms. Comments and literals retain their
contents. Dynamic `select from cases` expressions retain their syntax.

Streaming channel writes use ordinary `for ... of ...` loops or
`for await ... of ...` over an async iterator. Both braced loops and `do`/`done`
loops use two-space nesting:

```ores
for const value of values do
  writech output, value;
done
for await const value of events() {
  await nb writech output, value;
}
```

The formatter is intentionally conservative about grammar that is still
changing: it does not reorder declarations, imports, traits, interfaces, or
class conformance lists.

For semantic safety, multiline string/template literals currently fail closed
instead of being rewritten. This prevents indentation, trailing-whitespace, or
line-ending normalization from changing literal runtime bytes while parser-backed
literal preservation is still being completed.

## CLI

```bash
cargo install --path .

# default: preview only, never modify files
oresfmt src examples
# would format src/foo.ores

# explicit in-place rewrite
oresfmt --write src examples

# CI / pre-commit mode; exits 1 if anything would change
oresfmt --check .

# stdin -> stdout
oresfmt - < input.ores

# one file -> stdout
oresfmt --stdout example.ores
```

The installed binary is `oresfmt`; `oreslang-format` is also provided as an
alias. Directories are walked recursively and only `.ores` files are selected.

### Safe writes

Filesystem inputs are **dry-run by default**. `--write` is the only normal mode
that modifies files, and it performs a full preflight before touching any file.
This prevents a later unsafe path from leaving a project half-formatted.

For every file that would change, `--write` fails closed when the file is:

- tracked by Git but has staged or unstaged changes;
- untracked or ignored;
- outside a Git worktree.

The overrides are intentionally explicit:

```bash
oresfmt --write --ok-to-mod-dirty-files src
oresfmt --write --ok-to-mod-untracked-files generated
oresfmt --write --ok-to-mod-outside-git /tmp/example.ores
```

These flags only relax write-safety checks. They do not change formatting style.

Additional write hardening:

- explicit symlink inputs are refused, and symlinks found during recursive walks
  are skipped rather than followed;
- after the full Git preflight, every file is re-read before the first write, so
  a concurrent editor/generator change aborts the operation instead of being
  overwritten from a stale formatter snapshot;
- write-safety override flags are rejected unless `--write` is active.

## Rust SDK

```rust
use oreslang_format::{format_source, is_formatted};

let formatted = format_source(source)?;
let clean = is_formatted(&formatted)?;
assert!(clean);
```

`format_source` is idempotent: formatting canonical output again produces the
same bytes.

## Why no configuration?

Oreslang should have one mechanically enforceable source style. This avoids
project-specific formatter drift and gives compiler diagnostics, generated
code, examples, editor integrations, and code review the same layout contract.
