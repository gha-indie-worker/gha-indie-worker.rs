#include "oreslang/runtime/GpuBufferFence.hpp"
#include <stdexcept>
#include <iostream>
#include <type_traits>
#include <atomic>
#include <exception>
#include <optional>
#include <thread>

template<class Exception, class F> void expects(F fn) {
    try { fn(); } catch (const Exception&) { return; }
    throw std::runtime_error("GPU buffer fence expected rejection did not happen");
}
static void check(bool ok) {
    if (!ok) throw std::runtime_error("GPU buffer fence test failed");
}
int main() {
    using namespace oreslang::gpu;
    const Identity id{"tenant-A", "device-0", 7};
    const Identity foreign{"tenant-B", "device-0", 7};
    BufferFence buffer(id, 1024);
    expects<std::logic_error>([&] { (void)buffer.begin_command(foreign); });
    auto first = buffer.begin_command(id);
    check(buffer.status() == Status::InFlight);
    expects<std::logic_error>([&] { buffer.complete(first, true); });
    expects<std::logic_error>([&] { buffer.cancel_pending(first); });
    buffer.mark_submitted(first);
    expects<std::logic_error>([&] { buffer.mark_submitted(first); });
    expects<std::logic_error>([&] { buffer.abort_unsubmitted(first); });
    expects<std::logic_error>([&] { (void)buffer.take(); });
    expects<std::logic_error>([&] { buffer.close_after_retirement(); });
    static_assert(!std::is_default_constructible<Ticket>::value,
                  "native device tickets must not be guest-constructible");
    // Identical generation+sequence on another buffer must NOT authenticate
    // completion or cancellation of this buffer's in-flight work.
    BufferFence impostor(id, 1024);
    auto impostor_ticket = impostor.begin_command(id);
    impostor.mark_submitted(impostor_ticket);
    check(impostor_ticket.generation() == first.generation());
    check(impostor_ticket.sequence() == first.sequence());
    expects<std::logic_error>([&] { buffer.complete(impostor_ticket, true); });
    expects<std::logic_error>([&] { buffer.cancel_pending(impostor_ticket); });
    impostor.complete(impostor_ticket, true);
    impostor.retire_after_backend_release(id);
    impostor.close_after_retirement();

    // A reservation that never reached hardware may be rolled back exactly
    // once with its original ticket. Submitted work must use completion or
    // cancellation instead.
    {
        BufferFence reserved(id, 32);
        auto ticket = reserved.begin_command(id);
        reserved.abort_unsubmitted(ticket);
        check(reserved.status() == Status::Idle);
        expects<std::logic_error>([&] { reserved.abort_unsubmitted(ticket); });
        auto next_ticket = reserved.begin_command(id);
        check(next_ticket.sequence() != ticket.sequence());
        reserved.mark_submitted(next_ticket);
        expects<std::logic_error>([&] { reserved.abort_unsubmitted(next_ticket); });
        reserved.complete(next_ticket, true);
        reserved.retire_after_backend_release(id);
        reserved.close_after_retirement();
    }

    // C++ implicit move must not bypass take()'s in-flight constraint.
    expects<std::logic_error>([&] { BufferFence stolen(std::move(buffer)); });
    check(buffer.status() == Status::InFlight);
    BufferFence retired_target(id, 4);
    retired_target.retire_after_backend_release(id);
    retired_target.close_after_retirement();
    expects<std::logic_error>([&] { retired_target = std::move(buffer); });
    check(buffer.status() == Status::InFlight);
    buffer.complete(first, true);
    expects<std::logic_error>([&] { buffer.complete(first, true); });
    auto owner = buffer.take();
    expects<std::logic_error>([&] { (void)buffer.status(); });
    buffer.close_after_retirement(); // moved-from wrapper has no handle
    auto next = owner.begin_command(id);
    check(next.sequence() != first.sequence());
    expects<std::logic_error>([&] { owner.cancel_pending(next); });
    owner.mark_submitted(next);
    owner.cancel_pending(next);
    expects<std::logic_error>([&] { owner.acknowledge_cancelled_device_event(first, id); });
    check(owner.status() == Status::Poisoned);
    expects<std::logic_error>([&] { (void)owner.take(); });
    expects<std::logic_error>([&] { owner.retire_after_backend_release(id); });
    owner.acknowledge_cancelled_device_event(next, id);
    expects<std::logic_error>([&] { owner.acknowledge_cancelled_device_event(next, id); });
    owner.retire_after_backend_release(id);
    check(owner.status() == Status::Closed);
    check(owner.snapshot().status == Status::Closed);
    owner.close_after_retirement();
    expects<std::logic_error>([&] { owner.retire_after_backend_release(id); });
    expects<std::logic_error>([&] { (void)owner.begin_command(id); });

    const Identity second{"tenant", "device", 1};
    BufferFence failed(second, 4);
    auto t = failed.begin_command(second);
    failed.mark_submitted(t);
    failed.complete(t, false);
    check(failed.status() == Status::Poisoned);
    expects<std::logic_error>([&] { (void)failed.begin_command(second); });
    failed.retire_after_backend_release(second);
    check(failed.status() == Status::Closed);
    failed.close_after_retirement();
    expects<std::invalid_argument>([&] { (void)BufferFence(second, 0); });
    // Deterministic concurrent start: a command admission and transfer
    // compete on *the same* owner wrapper. Exactly one may succeed, and
    // ThreadSanitizer must observe no state_ or generation data races.
    for (int trial = 0; trial < 128; ++trial) {
        BufferFence contended(id, 16);
        std::optional<BufferFence> transferred;
        std::optional<Ticket> admitted;
        std::atomic<bool> go{false};
        std::exception_ptr admission_error, transfer_error;
        std::thread command([&] {
            while (!go.load(std::memory_order_acquire)) std::this_thread::yield();
            try { admitted = contended.begin_command(id); }
            catch (const std::logic_error&) { }
            catch (...) { admission_error = std::current_exception(); }
        });
        std::thread transfer([&] {
            while (!go.load(std::memory_order_acquire)) std::this_thread::yield();
            try { transferred.emplace(contended.take()); }
            catch (const std::logic_error&) { }
            catch (...) { transfer_error = std::current_exception(); }
        });
        go.store(true, std::memory_order_release);
        command.join();
        transfer.join();
        if (admission_error) std::rethrow_exception(admission_error);
        if (transfer_error) std::rethrow_exception(transfer_error);
        check(admitted.has_value() != transferred.has_value());
        if (admitted) {
            contended.mark_submitted(*admitted);
            contended.complete(*admitted, true);
            contended.retire_after_backend_release(id);
            contended.close_after_retirement();
        } else {
            transferred->retire_after_backend_release(id);
            transferred->close_after_retirement();
            expects<std::logic_error>([&] { (void)contended.status(); });
        }
    }
    // Closed-state observation must be race-free with retirement. The status
    // reader may see Idle or Closed, but must never dereference a transiently
    // closed wrapper through the old active() path.
    for (int trial = 0; trial < 128; ++trial) {
        BufferFence retiring(id, 8);
        std::atomic<bool> go{false};
        std::exception_ptr reader_error, retire_error;
        std::thread reader([&] {
            while (!go.load(std::memory_order_acquire)) std::this_thread::yield();
            try {
                for (;;) {
                    const auto observed = retiring.status();
                    if (observed == Status::Closed) break;
                    if (observed != Status::Idle)
                        throw std::runtime_error("unexpected retirement status");
                    std::this_thread::yield();
                }
            } catch (...) { reader_error = std::current_exception(); }
        });
        std::thread retire([&] {
            while (!go.load(std::memory_order_acquire)) std::this_thread::yield();
            try { retiring.retire_after_backend_release(id); }
            catch (...) { retire_error = std::current_exception(); }
        });
        go.store(true, std::memory_order_release);
        reader.join();
        retire.join();
        if (reader_error) std::rethrow_exception(reader_error);
        if (retire_error) std::rethrow_exception(retire_error);
        check(retiring.status() == Status::Closed);
        retiring.close_after_retirement();
    }

    // Move assignment must not orphan an unretired underlying buffer.
    BufferFence existing(id, 4), incoming(id, 4);
    expects<std::logic_error>([&] { existing = std::move(incoming); });
    check(existing.status() == Status::Idle);
    check(incoming.status() == Status::Idle);
    existing.retire_after_backend_release(id);
    existing.close_after_retirement();
    existing = std::move(incoming);
    check(existing.status() == Status::Idle);
    existing.retire_after_backend_release(id);
    existing.close_after_retirement();
    std::cout << "native device fence lifecycle tests passed\n";
}
