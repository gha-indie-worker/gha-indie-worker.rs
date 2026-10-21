#pragma once

#include "oreslang/runtime/DopKernelIR.hpp"
#include "oreslang/runtime/GpuBufferFence.hpp"

#include <cstdint>
#include <limits>
#include <memory>
#include <string>
#include <stdexcept>
#include <type_traits>
#include <utility>

namespace oreslang::dop {

// Trusted-provider descriptor. Byte length alone never establishes whether
// storage is AoS, SoA or AoSoA; an equal-size, wrong-layout buffer is invalid.
struct DeviceBuffer {
    const Plan layout; // immutable after binding; no concurrent shape substitution
    gpu::BufferFence& fence;

    DeviceBuffer(Plan shape, gpu::BufferFence& owner)
        : layout(std::move(shape)), fence(owner) {
        const auto descriptor = fence.snapshot();
        if (layout.rows() == 0 || layout.byte_length() == 0
                || descriptor.bytes != layout.byte_length())
            throw std::invalid_argument("DOP GPU device allocation and layout disagree");
    }
};

struct GpuBinding {
    Plan layout;
    OpenClArtifact artifact;
    gpu::Identity input;
    gpu::Identity output;
    std::uint64_t bytes_per_buffer;
    std::uint64_t total_reserved_bytes;
};

// This is a checked PRE-DISPATCH PLAN, not an allocation, asynchronous
// transfer, device execution, ownership move or security capability.
// The real backend must atomically revalidate while admitting ownership and
// hold each fence until authenticated device completion and physical release.
inline GpuBinding preflight_gpu(
        const Kernel& kernel, GpuTarget target,
        const DeviceBuffer& input, const DeviceBuffer& output) {
    if (&input.fence == &output.fence)
        throw std::invalid_argument("DOP GPU input/output owners must be distinct");
    const auto artifact = lower_opencl(kernel, target);
    const auto& layout = kernel.layout;
    const auto bytes = layout.byte_length();
    if (layout.rows() == 0 || bytes == 0 || artifact.required_bytes != bytes)
        throw std::invalid_argument("DOP GPU requires nonempty exact layout");
    if (!matching_layout(layout, input.layout)
            || !matching_layout(layout, output.layout))
        throw std::invalid_argument("DOP GPU kernel and buffers require identical layouts");

    // Separate input and output device allocations require two reservations.
    const auto total = checked_mul(bytes, 2);
    if (total > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
        throw std::overflow_error("DOP GPU reservation exceeds signed 64-bit ABI");
    const auto src = input.fence.snapshot();
    const auto dst = output.fence.snapshot();
    if (src.status != gpu::Status::Idle || dst.status != gpu::Status::Idle)
        throw std::logic_error("DOP GPU buffer not idle");
    if (src.bytes != bytes || dst.bytes != bytes)
        throw std::invalid_argument("DOP GPU buffer bytes must exactly match padded layout");
    if (src.identity.allocation_generation == 0
            || dst.identity.allocation_generation == 0
            || src.identity.allocation_generation == dst.identity.allocation_generation
            || src.identity.tenant != dst.identity.tenant
            || src.identity.device != dst.identity.device)
        throw std::invalid_argument(
                "DOP GPU requires same tenant/device and distinct nonzero generations");
    return GpuBinding{layout, artifact, src.identity, dst.identity, bytes, total};
}

/** Provider-owned *description* of a selected GPU queue/stream. It is NOT
 * an authenticated capability and MUST NOT be constructed by untrusted guest
 * source. A real provider must authenticate the device, queue generation,
 * tenant quota and actual occupancy again at physical submission. */
struct GpuQueueLocation {
    std::string tenant;
    std::string device;
    std::uint64_t queue_id;
    std::uint64_t generation;
    std::uint32_t maximum_in_flight;
};

/** This check happens BEFORE claiming either buffer fence. A queue
 * with mismatched tenant/device or missing generation is rejected; freshness
 * of a nonzero generation is NOT established without provider attestation.
 * No host pointer, actor-state borrow or CPU fallback is authorized here. */
inline void validate_gpu_queue(const GpuBinding& plan,
                               const GpuQueueLocation& queue) {
    if (queue.tenant.empty() || queue.device.empty()
            || queue.queue_id == 0 || queue.generation == 0
            || queue.maximum_in_flight == 0) {
        throw std::invalid_argument("DOP GPU queue identity/limits missing");
    }
    if (queue.tenant != plan.input.tenant
            || queue.tenant != plan.output.tenant
            || queue.device != plan.input.device
            || queue.device != plan.output.device) {
        throw std::invalid_argument(
                "DOP GPU queue and buffer tenant/device mismatch");
    }
}

struct ClaimedGpuBinding {
    GpuBinding binding;
    gpu::BufferFence::PairTickets tickets;
};

/* Immutable selection receipt. Queue IDs and generations are metadata,
 * not device capabilities; provider attestation remains mandatory at
 * actual submission. The selected queue itself cannot be replaced by a
 * caller after fence admission. */
class QueueClaimedGpuBinding final {
public:
    QueueClaimedGpuBinding(const QueueClaimedGpuBinding&) = delete;
    QueueClaimedGpuBinding& operator=(const QueueClaimedGpuBinding&) = delete;
    QueueClaimedGpuBinding(QueueClaimedGpuBinding&&) noexcept = default;
    QueueClaimedGpuBinding& operator=(QueueClaimedGpuBinding&&) = delete;

    const ClaimedGpuBinding& claim() const {
        if (!queue_)
            throw std::logic_error("DOP GPU claim receipt has been moved");
        return claim_;
    }

    const GpuQueueLocation& queue() const {
        if (!queue_)
            throw std::logic_error("DOP GPU queue receipt has been moved");
        return *queue_;
    }

private:
    // Do not allow hosts to fabricate a queue-scoped receipt from arbitrary
    // fence tickets and a chosen descriptor. Only the preflight+claim path
    // below can establish that the two identities matched this queue.
    friend QueueClaimedGpuBinding claim_gpu_for_queue(
            const Kernel&, GpuTarget,
            const DeviceBuffer&, const DeviceBuffer&,
            const GpuQueueLocation&);

    QueueClaimedGpuBinding(ClaimedGpuBinding&& admitted,
                          std::unique_ptr<const GpuQueueLocation> selected) noexcept
        : claim_(std::move(admitted)), queue_(std::move(selected)) {}

    ClaimedGpuBinding claim_;
    std::unique_ptr<const GpuQueueLocation> queue_;
};
static_assert(std::is_nothrow_move_constructible_v<QueueClaimedGpuBinding>,
              "post-fence queue claim return must not throw");

// After the two fences become IN_FLIGHT, building the return value must not
// allocate or throw. Keep these guarantees checked if the IR types evolve.
static_assert(std::is_nothrow_move_constructible_v<GpuBinding>,
              "GPU IR return must not throw after atomic fence admission");
static_assert(std::is_nothrow_move_constructible_v<gpu::BufferFence::PairTickets>,
              "GPU ticket return must not throw after atomic fence admission");
static_assert(std::is_nothrow_move_constructible_v<ClaimedGpuBinding>,
              "typed GPU claim return must not throw after atomic fence admission");

// A typed preflight is only advisory until this transaction RESERVES BOTH
// allocation fences. Reservation is not physical submission: a trusted
// provider must either mark the exact pair submitted after enqueue succeeds or
// abort the exact unsubmitted pair if enqueue never happened.
inline ClaimedGpuBinding claim_gpu(const Kernel& kernel, GpuTarget target,
                                  const DeviceBuffer& input, const DeviceBuffer& output) {
    auto binding = preflight_gpu(kernel, target, input, output);
    auto tickets = gpu::BufferFence::claim_pair(input.fence, output.fence,
                                               binding.bytes_per_buffer,
                                               &binding.input, &binding.output);
    return ClaimedGpuBinding{std::move(binding), std::move(tickets)};
}

/** Queue-targeted trusted-host claim. Unlike the legacy queue-neutral
 * helper, a provider calling this path cannot claim wrong-device buffers.
 * Successful host fence admission is a reservation, NOT physical launch/queue
 * capacity and NOT proof that the command reached hardware. */
inline QueueClaimedGpuBinding claim_gpu_for_queue(
        const Kernel& kernel, GpuTarget target,
        const DeviceBuffer& input, const DeviceBuffer& output,
        const GpuQueueLocation& queue) {
    auto binding = preflight_gpu(kernel, target, input, output);
    validate_gpu_queue(binding, queue);
    // Copying strings may throw: snapshot the selected queue BEFORE
    // committing either buffer to IN_FLIGHT; no post-fence allocations.
    auto selected = std::make_unique<const GpuQueueLocation>(queue);
    auto tickets = gpu::BufferFence::claim_pair(input.fence, output.fence,
            binding.bytes_per_buffer, &binding.input, &binding.output);
    return QueueClaimedGpuBinding{
            ClaimedGpuBinding{std::move(binding), std::move(tickets)},
            std::move(selected)};
}


inline void mark_gpu_submitted(
        const DeviceBuffer& input,
        const DeviceBuffer& output,
        const ClaimedGpuBinding& admitted) {
    gpu::BufferFence::mark_pair_submitted(
            input.fence, output.fence, admitted.tickets);
}

inline void abort_gpu_before_enqueue(
        const DeviceBuffer& input,
        const DeviceBuffer& output,
        const ClaimedGpuBinding& admitted) {
    gpu::BufferFence::abort_unsubmitted_pair(
            input.fence, output.fence, admitted.tickets);
}

inline void mark_gpu_submitted(
        const DeviceBuffer& input,
        const DeviceBuffer& output,
        const QueueClaimedGpuBinding& admitted) {
    mark_gpu_submitted(input, output, admitted.claim());
}

inline void abort_gpu_before_enqueue(
        const DeviceBuffer& input,
        const DeviceBuffer& output,
        const QueueClaimedGpuBinding& admitted) {
    abort_gpu_before_enqueue(input, output, admitted.claim());
}


} // namespace oreslang::dop
