# Literal string replacement

`value.replace_literal(needle, replacement)` returns a new string with every
non-overlapping occurrence of the nonempty `needle` replaced from left to right.
Both arguments must be strings. An empty needle is a runtime error.

The operation is literal: regex metacharacters, dollar signs, and backslashes
have no special meaning. Inserted replacement text is not searched again.
Unmatched text is preserved without normalization, including supplementary
Unicode characters. The current string storage uses UTF-16 code units; this
operation does not introduce a character-indexing contract.

This is a storage/runtime primitive, available without Java interop or host
access. The Truffle interpreter adapts its existing string storage; libraries
implement context-sensitive policy in Oreslang. It is not an HTML, JavaScript,
CSS, or URL sanitizer.

For HTML escaping, library code can replace ampersands first, followed by the
other delimiters. Each call returns a new immutable string. A native UTF-8
builder and streaming writer remain separate work under #246; this primitive
does not claim allocation-free rendering or complete that performance work.
