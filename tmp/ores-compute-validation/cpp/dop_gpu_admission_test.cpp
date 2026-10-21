#include "oreslang/runtime/DopGpuAdmission.hpp"
#include <cstdint>
#include <atomic>
#include <thread>
#include <optional>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>

static void check(bool condition) {
    if (!condition) throw std::runtime_error("DOP GPU admission assertion failed");
}
template<class E, class F> static void expect(F action) {
    try { action(); } catch (const E&) { return; }
    throw std::runtime_error("missing DOP GPU admission rejection");
}

int main() {
    using namespace oreslang::dop;
    using oreslang::gpu::BufferFence;
    using oreslang::gpu::Identity;
    for (const auto kind : {Layout::AoS, Layout::SoA, Layout::AoSoA}) {
        const Plan plan(kind, 7, 3, 4, 16, kind == Layout::AoSoA ? 4u : 1u);
        const Kernel kernel(plan, Element::F32, Op::ScaleBias, 2.0f, 1.0f);
        BufferFence src(Identity{"tenant", "device-0", 7}, plan.byte_length());
        BufferFence dst(Identity{"tenant", "device-0", 8}, plan.byte_length());
        const DeviceBuffer input(plan, src), output(plan, dst);
        const auto binding = preflight_gpu(kernel, {64, 8192, 100, true}, input, output);
        check(matching_layout(binding.layout, plan));
        check(binding.bytes_per_buffer == plan.byte_length());
        check(binding.total_reserved_bytes == plan.byte_length() * 2);
        check(binding.artifact.required_bytes == plan.byte_length());
        check(binding.artifact.global_rows == 7 && binding.artifact.global_fields == 3);
        check(binding.input.allocation_generation != binding.output.allocation_generation);
    }

    // Equal padded byte lengths do not imply equal field offsets.
    const Plan aos(Layout::AoS, 2, 2, 4, 8, 1);
    const Plan soa(Layout::SoA, 2, 2, 4, 8, 1);
    check(aos.byte_length() == soa.byte_length());
    check(aos.byte_offset(0, 1) != soa.byte_offset(0, 1));
    const Kernel kernel(aos, Element::F32, Op::Scale, 2, 0);
    BufferFence a(Identity{"t", "d", 1}, aos.byte_length());
    BufferFence b(Identity{"t", "d", 2}, aos.byte_length());
    const DeviceBuffer input(aos, a), output(aos, b), wrong_layout(soa, b);
    const GpuTarget target{64, 8192, 100, true};
    expect<std::invalid_argument>([&] { (void)preflight_gpu(kernel, target, wrong_layout, output); });
    expect<std::invalid_argument>([&] { (void)preflight_gpu(kernel, target, input, wrong_layout); });
    expect<std::invalid_argument>([&] { (void)preflight_gpu(kernel, target, input, input); });
    expect<std::invalid_argument>([&] {
        (void)preflight_gpu(kernel, target, input, DeviceBuffer(aos, a));
    });
    expect<std::invalid_argument>([&] {
        BufferFence wrong_bytes(Identity{"t", "d", 3}, aos.byte_length() + 4);
        (void)DeviceBuffer(aos, wrong_bytes);
    });
    for (const auto& foreign : {Identity{"other", "d", 3}, Identity{"t", "other", 3},
                               Identity{"t", "d", 1}, Identity{"t", "d", 0}}) {
        BufferFence foreign_fence(foreign, aos.byte_length());
        const DeviceBuffer candidate(aos, foreign_fence);
        expect<std::invalid_argument>([&] {
            (void)preflight_gpu(kernel, target, input, candidate);
        });
    }
    expect<std::invalid_argument>([&] {
        (void)preflight_gpu(kernel, {32, 8192, 100, true}, input, output);
    });
    expect<std::invalid_argument>([&] {
        (void)preflight_gpu(kernel, {64, 1, 100, true}, input, output);
    });

    const auto ticket = a.begin_command(Identity{"t", "d", 1});
    expect<std::logic_error>([&] { (void)preflight_gpu(kernel, target, input, output); });
    a.mark_submitted(ticket);
    a.cancel_pending(ticket);
    expect<std::logic_error>([&] { (void)preflight_gpu(kernel, target, input, output); });
    a.acknowledge_cancelled_device_event(ticket, Identity{"t", "d", 1});
    a.retire_after_backend_release(Identity{"t", "d", 1});
    a.close_after_retirement();

    const Plan empty(Layout::SoA, 0, 1, 4, 8, 1);
    const Kernel empty_kernel(empty, Element::F32, Op::Scale, 2, 0);
    expect<std::invalid_argument>([&] {
        (void)lower_opencl(empty_kernel, {64, 8192, 100, true});
    });
    expect<std::invalid_argument>([&] {
        BufferFence placeholder(Identity{"t", "d", 11}, 4);
        (void)DeviceBuffer(empty, placeholder);
    });

    // Native uint64_t can represent two buffers that Java long cannot:
    // require signed-64 ABI parity, without actually allocating huge storage.
    const Plan huge(Layout::AoS,
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) / 8 + 1,
            1, 4, 4, 1);
    BufferFence huge_a(Identity{"t", "d", 100}, huge.byte_length());
    BufferFence huge_b(Identity{"t", "d", 101}, huge.byte_length());
    const DeviceBuffer ha(huge, huge_a), hb(huge, huge_b);
    const Kernel huge_kernel(huge, Element::F32, Op::Scale, 2, 0);
    expect<std::overflow_error>([&] {
        (void)preflight_gpu(huge_kernel, {64,
                std::numeric_limits<std::uint64_t>::max(),
                std::numeric_limits<std::uint64_t>::max(), true}, ha, hb);
    });


    // Claiming typed DOP buffers starts both fences together.
    {
        const Plan shape(Layout::AoSoA, 7, 3, 4, 16, 4);
        const Kernel operation(shape, Element::F32, Op::Scale, 2.0f, 0.0f);
        BufferFence source(Identity{"t", "gpu", 10}, shape.byte_length());
        BufferFence result(Identity{"t", "gpu", 11}, shape.byte_length());
        DeviceBuffer in(shape, source), out(shape, result);
        auto admitted = claim_gpu(operation, {64, 8192, 100, true}, in, out);
        check(admitted.binding.bytes_per_buffer == shape.byte_length());
        check(source.status() == oreslang::gpu::Status::InFlight);
        check(result.status() == oreslang::gpu::Status::InFlight);
        // Other allocations may legitimately have matching numerical
        // generation and sequence, but not this submission's private seal.
        BufferFence foreign(Identity{"t", "gpu", 10}, shape.byte_length());
        const auto forged = foreign.begin_command(Identity{"t", "gpu", 10});
        foreign.mark_submitted(forged);
        check(forged.generation() == admitted.tickets.input_ticket.generation()
                && forged.sequence() == admitted.tickets.input_ticket.sequence());
        expect<std::logic_error>([&] { source.complete(forged, true); });
        expect<std::logic_error>([&] { source.cancel_pending(forged); });
        foreign.complete(forged, true);
        expect<std::logic_error>([&] { (void)source.take(); });
        expect<std::logic_error>([&] { (void)result.take(); });
        mark_gpu_submitted(in, out, admitted);
        BufferFence::complete_pair(source, result, admitted.tickets, true);
        check(source.status() == oreslang::gpu::Status::Idle);
        check(result.status() == oreslang::gpu::Status::Idle);
    }

    // If either buffer is busy, a rejected pair claim never changes the other.
    {
        BufferFence available(Identity{"t", "gpu", 20}, 64);
        BufferFence busy(Identity{"t", "gpu", 21}, 64);
        auto original = busy.begin_command(Identity{"t", "gpu", 21});
        busy.mark_submitted(original);
        expect<std::logic_error>([&] { (void)BufferFence::claim_pair(available, busy, 64); });
        check(available.status() == oreslang::gpu::Status::Idle);
        check(busy.status() == oreslang::gpu::Status::InFlight);
        busy.complete(original, true);
        expect<std::invalid_argument>([&] { (void)BufferFence::claim_pair(available, busy, 16); });
        check(available.status() == oreslang::gpu::Status::Idle);
        check(busy.status() == oreslang::gpu::Status::Idle);
        auto moved = busy.take();
        expect<std::logic_error>([&] { (void)BufferFence::claim_pair(available, busy, 64); });
        auto claimed = BufferFence::claim_pair(available, moved, 64);
        BufferFence::mark_pair_submitted(available, moved, claimed);
        BufferFence::cancel_pair(available, moved, claimed);
        expect<std::logic_error>([&] { (void)BufferFence::claim_pair(available, moved, 64); });
        BufferFence::acknowledge_cancelled_pair(available, moved, claimed);
        available.retire_after_backend_release(claimed.input);
        moved.retire_after_backend_release(claimed.output);
    }


    // A paired reservation that never reaches the device queue may roll back
    // atomically. Once submitted, only completion/cancellation may terminate it.
    {
        BufferFence left(Identity{"t", "gpu", 30}, 64);
        BufferFence right(Identity{"t", "gpu", 31}, 64);
        auto reserved = BufferFence::claim_pair(left, right, 64);
        expect<std::logic_error>([&] {
            BufferFence::complete_pair(left, right, reserved, true);
        });
        BufferFence::abort_unsubmitted_pair(left, right, reserved);
        check(left.status() == oreslang::gpu::Status::Idle);
        check(right.status() == oreslang::gpu::Status::Idle);
        expect<std::logic_error>([&] {
            BufferFence::abort_unsubmitted_pair(left, right, reserved);
        });

        auto submitted = BufferFence::claim_pair(left, right, 64);
        BufferFence::mark_pair_submitted(left, right, submitted);
        expect<std::logic_error>([&] {
            BufferFence::abort_unsubmitted_pair(left, right, submitted);
        });
        BufferFence::complete_pair(left, right, submitted, true);
    }

    // Opposite-order concurrent claims must never deadlock or half-claim.
    for (int iteration = 0; iteration < 100; ++iteration) {
        BufferFence left(Identity{"t", "gpu", 50}, 64);
        BufferFence right(Identity{"t", "gpu", 51}, 64);
        std::atomic<bool> start{false};
        std::atomic<int> wins{0}, rejects{0};
        std::optional<BufferFence::PairTickets> results[2];
        auto attempt = [&](int slot, BufferFence& input, BufferFence& output) {
            while (!start.load(std::memory_order_acquire)) std::this_thread::yield();
            try {
                results[slot] = BufferFence::claim_pair(input, output, 64);
                wins.fetch_add(1, std::memory_order_relaxed);
            } catch (const std::logic_error&) {
                rejects.fetch_add(1, std::memory_order_relaxed);
            }
        };
        std::thread t1(attempt, 0, std::ref(left), std::ref(right));
        std::thread t2(attempt, 1, std::ref(right), std::ref(left));
        start.store(true, std::memory_order_release);
        t1.join();
        t2.join();
        check(wins.load() == 1 && rejects.load() == 1);
        check(left.status() == oreslang::gpu::Status::InFlight);
        check(right.status() == oreslang::gpu::Status::InFlight);
        const auto& winner = results[0].has_value() ? *results[0] : *results[1];
        BufferFence& src = results[0].has_value() ? left : right;
        BufferFence& dst = results[0].has_value() ? right : left;
        BufferFence::mark_pair_submitted(src, dst, winner);
        BufferFence::complete_pair(src, dst, winner, true);
    }

    for (int i = 0; i < 50; ++i) {
        BufferFence left(Identity{"t", "d", 101}, 16);
        BufferFence right(Identity{"t", "d", 102}, 16);
        std::optional<BufferFence::PairTickets> tickets;
        std::optional<BufferFence> moved;
        std::thread claimant([&] {
            try { tickets = BufferFence::claim_pair(left, right, 16); }
            catch (const std::logic_error&) { }
        });
        std::thread mover([&] {
            try { moved.emplace(right.take()); }
            catch (const std::logic_error&) { }
        });
        claimant.join(); mover.join();
        check(tickets.has_value() != moved.has_value());
        if (tickets) {
            BufferFence::mark_pair_submitted(left, right, *tickets);
            BufferFence::complete_pair(left, right, *tickets, true);
        } else check(left.status() == oreslang::gpu::Status::Idle);
    }

    {
        // A foreign ticket carrying matching numbers cannot commit a pair.
        BufferFence a(Identity{"tenant", "gpu", 501}, 64);
        BufferFence b(Identity{"tenant", "gpu", 502}, 64);
        BufferFence foreign(Identity{"tenant", "gpu", 501}, 64);
        auto tickets = BufferFence::claim_pair(a, b, 64);
        auto other_ticket = foreign.begin_command(Identity{"tenant", "gpu", 501});
        foreign.mark_submitted(other_ticket);
        check(tickets.input_ticket.sequence() == other_ticket.sequence());
        BufferFence::mark_pair_submitted(a, b, tickets);
        auto mixed = tickets;
        mixed.input_ticket = other_ticket;
        expect<std::logic_error>([&] {
            BufferFence::complete_pair(a, b, mixed, true);
        });
        check(a.status() == oreslang::gpu::Status::InFlight);
        check(b.status() == oreslang::gpu::Status::InFlight);
        BufferFence::complete_pair(a, b, tickets, true);
        check(a.status() == oreslang::gpu::Status::Idle);
        check(b.status() == oreslang::gpu::Status::Idle);
        expect<std::logic_error>([&] {
            BufferFence::complete_pair(a, b, tickets, true);
        });
        foreign.complete(other_ticket, true);
    }
    {
        BufferFence a(Identity{"tenant", "gpu", 601}, 64);
        BufferFence b(Identity{"tenant", "gpu", 602}, 64);
        auto tickets = BufferFence::claim_pair(a, b, 64);
        BufferFence::mark_pair_submitted(a, b, tickets);
        BufferFence::cancel_pair(a, b, tickets);
        check(a.status() == oreslang::gpu::Status::Poisoned);
        check(b.status() == oreslang::gpu::Status::Poisoned);
        expect<std::logic_error>([&] { (void)a.take(); });
        expect<std::logic_error>([&] { (void)b.take(); });
        expect<std::logic_error>([&] {
            a.retire_after_backend_release(tickets.input);
        });
        expect<std::logic_error>([&] {
            b.retire_after_backend_release(tickets.output);
        });
        expect<std::logic_error>([&] {
            BufferFence::complete_pair(a, b, tickets, false);
        });
        BufferFence::acknowledge_cancelled_pair(a, b, tickets);
        expect<std::logic_error>([&] {
            BufferFence::acknowledge_cancelled_pair(a, b, tickets);
        });
        a.retire_after_backend_release(tickets.input);
        b.retire_after_backend_release(tickets.output);
    }
    {
        BufferFence a(Identity{"tenant", "gpu", 701}, 64);
        BufferFence b(Identity{"tenant", "gpu", 702}, 64);
        auto tickets = BufferFence::claim_pair(a, b, 64);
        BufferFence::mark_pair_submitted(a, b, tickets);
        expect<std::logic_error>([&] { a.complete(tickets.input_ticket, true); });
        expect<std::logic_error>([&] { b.cancel_pending(tickets.output_ticket); });
        check(a.status() == oreslang::gpu::Status::InFlight);
        check(b.status() == oreslang::gpu::Status::InFlight);
        BufferFence::complete_pair(a, b, tickets, true);
    }
    // Two valid submissions cannot be recombined into a fabricated pair.
    {
        BufferFence a(Identity{"t", "d", 801}, 64), b(Identity{"t", "d", 802}, 64);
        BufferFence c(Identity{"t", "d", 803}, 64), d(Identity{"t", "d", 804}, 64);
        auto ab = BufferFence::claim_pair(a, b, 64);
        auto cd = BufferFence::claim_pair(c, d, 64);
        BufferFence::mark_pair_submitted(a, b, ab);
        BufferFence::mark_pair_submitted(c, d, cd);
        auto mixed = ab;
        mixed.output = cd.input;
        mixed.output_ticket = cd.input_ticket;
        expect<std::logic_error>([&] { BufferFence::complete_pair(a, c, mixed, true); });
        check(a.status() == oreslang::gpu::Status::InFlight);
        check(c.status() == oreslang::gpu::Status::InFlight);
        BufferFence::complete_pair(a, b, ab, true);
        BufferFence::complete_pair(c, d, cd, true);
    }
    // Owner replacement between preflight and claim must reject before mutation.
    {
        BufferFence a(Identity{"t", "d", 901}, 64), b(Identity{"t", "d", 902}, 64);
        const Identity stale{"t", "d", 900}, expected{"t", "d", 902};
        expect<std::invalid_argument>([&] {
            (void)BufferFence::claim_pair(a, b, 64, &stale, &expected);
        });
        check(a.status() == oreslang::gpu::Status::Idle);
        check(b.status() == oreslang::gpu::Status::Idle);
    }
    // Trusted host queue-targeted GPU preflight must fail BEFORE mutation.
    // This verifies metadata compatibility, not actual device execution.
    {
        const Plan shape(Layout::SoA, 7, 2, 4, 16, 1);
        const Kernel operation(shape, Element::F32, Op::Scale, 2.0f, 0.0f);
        const GpuTarget profile{64, 8192, 100, true};
        BufferFence source(Identity{"tenant-A", "gpu-0", 1001}, shape.byte_length());
        BufferFence result(Identity{"tenant-A", "gpu-0", 1002}, shape.byte_length());
        const DeviceBuffer input(shape, source), output(shape, result);
        const auto plan = preflight_gpu(operation, profile, input, output);
        const GpuQueueLocation valid{"tenant-A", "gpu-0", 9, 21, 4};
        validate_gpu_queue(plan, valid);
        for (const auto& foreign : {
                GpuQueueLocation{"tenant-B", "gpu-0", 9, 21, 4},
                GpuQueueLocation{"tenant-A", "gpu-1", 9, 21, 4},
                GpuQueueLocation{"tenant-A", "gpu-0", 0, 21, 4},
                GpuQueueLocation{"tenant-A", "gpu-0", 9, 0, 4},
                GpuQueueLocation{"tenant-A", "gpu-0", 9, 21, 0},
                GpuQueueLocation{"", "gpu-0", 9, 21, 4}}) {
            expect<std::invalid_argument>([&] {
                (void)claim_gpu_for_queue(operation, profile, input, output, foreign);
            });
            check(source.status() == oreslang::gpu::Status::Idle);
            check(result.status() == oreslang::gpu::Status::Idle);
        }
        GpuQueueLocation mutableSelected = valid;
        auto claimed = claim_gpu_for_queue(
                operation, profile, input, output, mutableSelected);
        // Mutating the original selection cannot change the admitted receipt.
        mutableSelected.tenant = "attacker";
        mutableSelected.device = "gpu-elsewhere";
        mutableSelected.queue_id = 999;
        mutableSelected.generation = 999;
        check(claimed.claim().binding.input.tenant == valid.tenant);
        check(claimed.claim().binding.output.device == valid.device);
        check(claimed.queue().queue_id == valid.queue_id);
        check(claimed.queue().generation == valid.generation);
        check(claimed.queue().maximum_in_flight == valid.maximum_in_flight);
        // Later mutation of the caller-owned descriptor cannot silently
        // retarget an admitted operation.
        check(claimed.queue().tenant == "tenant-A");
        check(claimed.queue().device == "gpu-0");
        static_assert(!std::is_copy_constructible_v<QueueClaimedGpuBinding>);
        static_assert(!std::is_constructible_v<
                QueueClaimedGpuBinding, ClaimedGpuBinding&&,
                std::unique_ptr<const GpuQueueLocation>>,
                "host code must not fabricate a queue claim without validation");
        static_assert(!std::is_move_assignable_v<QueueClaimedGpuBinding>,
                "an admitted queue receipt must not be retargeted by assignment");
        static_assert(std::is_same_v<
                decltype(claimed.claim()), const ClaimedGpuBinding&>,
                "a caller must not replace the admitted fence tokens or binding");
        static_assert(std::is_same_v<
                decltype(claimed.queue()), const GpuQueueLocation&>,
                "the selected queue must only be exposed as a const reference");
        check(source.status() == oreslang::gpu::Status::InFlight);
        check(result.status() == oreslang::gpu::Status::InFlight);
        expect<std::logic_error>([&] {
            (void)claim_gpu_for_queue(operation, profile, input, output, valid);
        });
        auto transferred = std::move(claimed);
        expect<std::logic_error>([&] { (void)claimed.queue(); });
        expect<std::logic_error>([&] { (void)claimed.claim(); });
        check(transferred.queue().queue_id == valid.queue_id);
        mark_gpu_submitted(input, output, transferred);
        BufferFence::complete_pair(source, result, transferred.claim().tickets, true);
        check(source.status() == oreslang::gpu::Status::Idle);
        check(result.status() == oreslang::gpu::Status::Idle);
    }

    std::cout << "typed DOP GPU placement admission tests passed\n";
}
