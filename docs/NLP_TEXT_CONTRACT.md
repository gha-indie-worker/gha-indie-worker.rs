# NLP text and byte-level BPE contract

This repository's first NLP slice is intentionally small: deterministic
byte-level BPE over caller-provided UTF-8 bytes.

## Why bytes first

Oreslang still needs one cross-backend standard-library primitive that exposes
a String as UTF-8 bytes or a borrow-checked UTF-8 byte view. The BPE algorithm
must not depend on backend-specific String internals, UTF-16 indexing, or host
libraries.

Until that adapter exists, callers may supply an already encoded
`List<int>` where every element is in `0..255`.

The BPE core itself is pure Oreslang in `src/nlp/bpe.ores`.

## Stable semantics

- byte token ids are exactly `0..255`;
- merge result ids are `>= 256`;
- each merge pair is unique;
- each rank is unique;
- the globally lowest-ranked adjacent merge is applied first;
- byte spans are half-open `[start, end)` offsets into the original UTF-8
  byte sequence;
- output buffers are caller-provided and the valid prefix is identified by
  `BpeEncoding.count`;
- no token text strings are allocated by the BPE core;
- malformed byte values and ambiguous merge tables return `Err`.

## Unicode boundary

BPE operates on bytes. Unicode normalization, grapheme segmentation, case
folding, word segmentation, and String-to-UTF-8 conversion belong in the text
layer above it.

The required cross-backend adapter should eventually have semantics equivalent
to:

```ores
using text.utf8_bytes() as bytes {
  val Result<BpeEncoding, String> encoded = encode_utf8_bytes_into(
    bytes,
    merges,
    token_ids,
    byte_starts,
    byte_ends
  );
}
```

The exact API name is not fixed here. The important contract is that the view
is read-only, UTF-8, lifetime-safe, zero-copy where the backend representation
permits it, and deterministic across Java/Graal, LLVM, .NET, WASM, and JS.

## Acceptance vectors

The reference tests cover:

- ASCII merge chains;
- global merge-rank ordering instead of accidental left-to-right greediness;
- two-byte UTF-8 text (`é`);
- four-byte UTF-8 text (`🙂`);
- empty input;
- invalid byte rejection;
- duplicate pair rejection;
- duplicate rank rejection.

`tests/bpe_reference.rs` is an independent executable reference and does
not count as native Oreslang compiler/runtime evidence.
