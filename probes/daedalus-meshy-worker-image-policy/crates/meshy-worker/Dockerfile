# syntax=docker/dockerfile:1.7

FROM rust:1.97.1-bookworm AS builder
WORKDIR /src

# Copy only the independently locked worker graph and the canonical job-control
# module it includes. This keeps the main fabrication-server lockfile and its
# private superproject dependencies out of the worker image build.
COPY crates/meshy-client crates/meshy-client
COPY crates/meshy-job crates/meshy-job
COPY crates/meshy-r2-archive crates/meshy-r2-archive
COPY crates/meshy-worker crates/meshy-worker
COPY src/job_control.rs src/job_control.rs

# Reconstruct the exact validated resolver artifact and verify both the compact
# source and resulting Cargo.lock before permitting a locked release build.
RUN ./crates/meshy-worker/prepare-lock.sh \
    && cargo build \
      --locked \
      --release \
      --manifest-path crates/meshy-worker/Cargo.toml \
      --bin meshy-job-worker

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 daedalus \
    && useradd --system --uid 10001 --gid 10001 --home-dir /var/lib/daedalus daedalus \
    && install -d -o 10001 -g 10001 \
         /var/lib/daedalus \
         /var/lib/daedalus/meshy-staging \
         /var/lib/daedalus/artifacts

COPY --from=builder \
  /src/crates/meshy-worker/target/release/meshy-job-worker \
  /usr/local/bin/meshy-job-worker

USER 10001:10001
ENTRYPOINT ["/usr/local/bin/meshy-job-worker"]
