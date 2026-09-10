# Native worker exact-checkout boundary

This slice connects the typed dispatch contract and active native-fleet lease to
the first operation a real Windows or macOS worker may perform: obtain the exact
reviewed repository commit. It is intentionally narrower than a generic
self-hosted GitHub Actions runner.

## Authority chain

`dispatch v2 -> active lease v1 -> execution handoff v1 -> checkout evidence v1`

The handoff is created only when the dispatch and lease agree on request ID and
digest, repository URL, exact commit SHA, fixed profile and digest, host
capability snapshot, and capability digest. Expired, canceled, terminal,
tampered, or mismatched leases fail before Git is invoked.

The resulting handoff contains no caller command, `uses` step, image, shell,
credential, branch, tag, or fallback reference. Its digest covers every field,
including the reviewed checkout policy.

## Checkout contract

The worker accepts only credential-free `https://github.com/owner/repository`
URLs and exactly 40 lowercase hexadecimal commit SHAs. It then:

1. creates an empty workspace and isolated Git configuration home;
2. disables prompts, hooks, LFS smudge, submodule recursion, system Git config,
   file transport, and automatic maintenance;
3. creates exactly one remote named `origin`;
4. fetches exactly the requested SHA with no tags and no ref fallback;
5. checks out `FETCH_HEAD` with detached `HEAD`;
6. verifies the resolved commit, origin URL, single-remote set, clean tracked
   state, tree SHA, and fixed context directory; and
7. rejects any submodule gitlink or persisted authentication/URL rewrite config.

A failed checkout removes a workspace created by the operation. A pre-existing
non-empty workspace or symlink is rejected rather than cleaned or reused.

## Evidence

Successful checkout evidence binds the request, lease, handoff digest, host,
profile, runner target, repository, requested and resolved commits, tree SHA,
origin, remote set, context directory, and timestamps. A final evidence digest
makes post-run mutation visible.

The checkout evidence is suitable as one input to the eventual run manifest. It
does not by itself prove sandbox isolation, cleanup, job execution, artifact
publication, GitHub check lifecycle, physical-host readiness, or production
machine identity.

## Cross-platform conformance

The permanent workflow runs the same unit and live public-repository checkout
corpus on fixed Ubuntu 24.04, Windows Server 2025, and macOS 15 hosted references.
The live fixture clones the pull-request head by exact SHA without a token and
verifies detached `HEAD` and evidence.

A separate `*-test` consumer must repeat the live checkout against its own exact
commit before integration promotion. Hosted references prove portability of this
boundary, not readiness of an independent physical fleet.

## Deliberate exclusions

This slice does not execute workflow steps, synthesize credentials, recurse
submodules, download LFS objects, select an image, mount host sockets, mutate
Kubernetes, deploy, sign releases, or silently approximate unsupported GitHub
Actions behavior. Future capabilities must be fixed-profile policy with separate
review and evidence rather than fallback behavior here.
