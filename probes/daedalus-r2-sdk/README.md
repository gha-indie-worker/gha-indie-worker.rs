# Daedalus R2 SDK surface probe

This public probe verifies only the generic AWS SDK for Rust calls used by the private Daedalus Cloudflare R2 archive adapter in `daedalus-fab/fabrication-server.rs#8` at source head `8244c0d22c9e1287fb4e112b4e6ab18ffc4c635e`.

It deliberately contains no Daedalus business logic, credentials, endpoint, bucket, object key, provider task, tenant identifier, or private source. It compiles these SDK assumptions independently of the source organization's temporarily disabled hosted Actions:

- `BehaviorVersion::v2026_01_12()`;
- static `Credentials` and `Region("auto")`;
- custom endpoint plus S3 path-style configuration;
- `ByteStream::from_path`;
- conditional `PutObject.if_none_match("*")`;
- metadata and content-type setters;
- `HeadObjectOutput` content-length, content-type, and metadata accessors;
- `SdkError::as_service_error`, `HeadObjectError::is_not_found`, `SdkError::raw_response`, and HTTP status extraction.

The probe does not contact R2, consume Meshy credits, or contain secrets. Passing it establishes SDK surface compatibility, not full Daedalus archive correctness; the private PR retains the immutable/idempotency tests and release-boundary contract.
