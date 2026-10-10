# Oreslang Conformance Contract v1

One specification, **two independent compilers and borrow checkers**.
This package defines a *portable test interchange format*, not a shared AST,
implementation, machine ABI, or source-language grammar. The Java language
docs/grammar remain the current reference for source semantics.

- `main.tsp`: TypeSpec data shapes for suite, case and normalized result.
- `schema/*.schema.json`: committed strict JSON Schema 2020-12 validation
  (deliberately adds path/ID/provenance constraints beyond TypeSpec's basic shapes).
- `cases.json`: normative admission outcome, independent of backend availability.
- `fixtures/*.ores`: one executable/negative source corpus, no host dependencies.
- `run.py`: backend-independent admission runner; outputs structured JSON reports.

## Run

```sh
python3 -m pip install 'jsonschema>=4.20,<5'
python3 contracts/v1/run.py --validate
python3 contracts/v1/run.py --backend llvm \
  --command './build/oreslang-llvmc {source}' \
  --report build/conformance-llvm.json
# In the Java repository, after Maven dependencies are available:
python3 contracts/v1/run.py --backend graal \
  --command 'mvn -B -ntp -q -DskipTests exec:java -Dexec.args="--check {source}"' \
  --report target/conformance-graal.json
```

TypeSpec is compiled independently:

```sh
cd contracts/v1
npm install --no-audit --no-fund
npm run build  # emits TypeSpec JSON Schema models
```

## Interpretation and stability

- `admission: accept/reject` states the normative parse/type/ownership result.
- `backends.*: required` is a merge-blocking backend expectation.
- `pending` is an explicitly unimplemented/uncertified case; it **does not pass**
  and must have a documented reason. No silent pass, skip, or xfail.
- Positive cases currently check **admission only**, not runtime result or
  native exit code. Existing LLVM CTest separately executes a few native
  programs. Do not infer semantic equivalence from compile admission alone.
- For portability, negative cases require rejection, not identical diagnostic
  wording. Stable diagnostic codes and runtime observational traces are future v1
  extensions; such changes must keep backward compatibility or version v2.
- Every case source and manifest is mirrored with identical Git blob hashes in
  both repos; do not silently modify one copy. Java reference
  `9d189677471398c98eb0995e15b711e1d08966ac` and LLVM reference
  `24043787e460388ef18a93b867c2803e29ec43f4` are baseline snapshots,
  **not** a claim that later heads passed.
- `--command` is tokenized with shlex, no shell; the explicit trusted compiler
  command supplies exactly one `{source}` substitution. Never execute guest
  code in the admission probe. Runtime isolation and execution suites are
  a separate, stronger test tier.

## Next phases

Runtime-value and error normalization; diagnostics with stable error codes;
lexer token snapshots; ownership/borrow metamorphic test generators; automatic
preemption and actor isolation/supervision traces; AOT-only/JIT/hybrid deployment
profiles and capability policy; platform-specific ABI/FFI contracts.
