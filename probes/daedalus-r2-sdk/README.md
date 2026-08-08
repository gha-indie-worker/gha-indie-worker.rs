# Daedalus R2 SDK surface probe

This public probe verifies only the generic AWS SDK for Rust calls used by the private Daedalus Cloudflare R2 archive adapter in `daedalus-fab/fabrication-server.rs#8` at source head `203a19d1376a4515d0769c632a68f66bf37541be`.

It deliberately contains no Daedalus business logic, credentials, endpoint, bucket, object key, provider task, tenant identifier, or private source. It compiles these SDK assumptions independently of the source organization's temporarily disabled hosted Actions:

- exact pins `aws-config = 1.10.0` and `aws-sdk-s3 = 1.140.0`;
- `BehaviorVersion::v2026_01_12()`;
- static `Credentials` and `Region("auto")`;
- custom endpoint plus S3 path-style configuration;
- `ByteStream::from_path`;
- conditional `PutObject.if_none_match("*")`;
- server-validated `PutObject.checksum_sha256(...)`;
- checksum-enabled `HeadObject` through `ChecksumMode::Enabled`;
- `HeadObjectOutput.checksum_sha256()` plus content-length, content-type, and metadata accessors;
- `SdkError::as_service_error`, `HeadObjectError::is_not_found`, `SdkError::raw_response`, and HTTP status extraction.

The companion dependency-only lockgraph mirrors the source package manifests for `dd-meshy-client`, `dd-meshy-job`, `dd-meshy-r2-archive`, and `dd-meshy-worker`. It resolves, compiles, and publishes the compact worker resolver artifact used by the private source PR without copying private implementation logic.

The probe does not contact R2, consume Meshy credits, or contain secrets. Passing it establishes SDK/dependency-surface compatibility, not full Daedalus archive correctness; the private PR retains the immutable/idempotency tests and release-boundary contract.
