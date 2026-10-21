#pragma once

#include <cstdint>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

namespace oreslang::gpu {

// Host-side descriptor/fence ledger ONLY. It does not allocate device memory,
// prove provider credentials or authorize dispatch; an external device backend
// must own the actual opaque handle and confirm quiescent physical release.
enum class Status { Idle, InFlight, Poisoned, Closed };
struct Identity {
    std::string tenant;
    std::string device;
    std::uint64_t allocation_generation;
    bool operator==(const Identity& other) const noexcept {
        return tenant == other.tenant && device == other.device
            && allocation_generation == other.allocation_generation;
    }
};
class BufferFence;
class Ticket {
public:
    std::uint64_t generation() const noexcept { return generation_; }
    std::uint64_t sequence() const noexcept { return sequence_; }

private:
    Ticket(std::uint64_t generation, std::uint64_t sequence,
           std::shared_ptr<const std::uint8_t> proof) noexcept
        : generation_(generation), sequence_(sequence), proof_(std::move(proof)) {}

    std::uint64_t generation_;
    std::uint64_t sequence_;
    // A unique, unexposed per-submission identity. Kept alive by the ticket;
    // numeric generation + sequence alone must never authorize completion.
    std::shared_ptr<const std::uint8_t> proof_;
    friend class BufferFence;
};

class BufferFence {
    struct State {
        Identity identity;
        std::uint64_t bytes;
        std::uint64_t sequence = 0;
        std::shared_ptr<const std::uint8_t> active_ticket_proof;
        Status status = Status::Idle;
        bool backend_quiescent = true;
        bool paired = false;
        std::shared_ptr<const std::uint8_t> active_pair_proof;
        mutable std::mutex lock;
        State(Identity i, std::uint64_t n)
            : identity(std::move(i)), bytes(n) {
            if (identity.tenant.empty() || identity.device.empty() || bytes == 0)
                throw std::invalid_argument("invalid device identity or byte limit");
        }
    };
public:
    BufferFence(Identity identity, std::uint64_t bytes)
        : state_(std::make_shared<State>(std::move(identity), bytes)) {}
    BufferFence(const BufferFence&) = delete;
    BufferFence& operator=(const BufferFence&) = delete;
    // The moved-from owner must never race with a method dereferencing
    // state_. All wrapper operations take owner_lock_ before State::lock.
    BufferFence(BufferFence&& other) {
        std::lock_guard<std::mutex> owner(other.owner_lock_);
        // A direct std::move must not bypass take()'s in-flight fence.
        if (other.state_) {
            std::lock_guard<std::mutex> guard(other.state_->lock);
            if (other.state_->status != Status::Idle)
                throw std::logic_error("cannot move device buffer with pending fence");
        }
        state_ = std::move(other.state_);
    }
    BufferFence& operator=(BufferFence&& other) {
        if (this == &other) return *this;
        std::scoped_lock owners(owner_lock_, other.owner_lock_);
        // Assignment must not silently abandon an unretired device handle.
        if (state_) {
            std::lock_guard<std::mutex> state_guard(state_->lock);
            if (state_->status != Status::Closed)
                throw std::logic_error("cannot overwrite unretired device buffer");
        }
        // Move assignment is an alternative ownership-transfer entrypoint;
        // validate the source too, before mutating either wrapper.
        if (other.state_) {
            std::lock_guard<std::mutex> guard(other.state_->lock);
            if (other.state_->status != Status::Idle)
                throw std::logic_error("cannot move device buffer with pending fence");
        }
        state_ = std::move(other.state_);
        return *this;
    }

    BufferFence take() {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->status != Status::Idle)
            throw std::logic_error("cannot move device buffer with pending fence");
        return BufferFence(std::move(state_));
    }

    struct PairTickets {
        Identity input;
        Ticket input_ticket;
        Identity output;
        Ticket output_ticket;
    };

    // All-or-nothing paired claim for a trusted GPU provider.
    // Owner and allocation locks are acquired in stable address order, so
    // take(), begin_command(), cancel and retirement cannot interleave.
    static PairTickets claim_pair(BufferFence& input, BufferFence& output,
                                  std::uint64_t expected_bytes,
                                  const Identity* expected_input = nullptr,
                                  const Identity* expected_output = nullptr) {
        if (&input == &output || expected_bytes == 0)
            throw std::invalid_argument("two nonempty distinct GPU buffers required");
        auto* first = std::less<BufferFence*>{}(&input, &output) ? &input : &output;
        auto* second = first == &input ? &output : &input;
        std::lock_guard<std::mutex> owner_one(first->owner_lock_);
        std::lock_guard<std::mutex> owner_two(second->owner_lock_);
        if (!input.state_ || !output.state_ || input.state_ == output.state_)
            throw std::logic_error("moved or aliased device owner");
        auto* a = input.state_.get();
        auto* b = output.state_.get();
        auto* low = std::less<State*>{}(a, b) ? a : b;
        auto* high = low == a ? b : a;
        std::lock_guard<std::mutex> state_one(low->lock);
        std::lock_guard<std::mutex> state_two(high->lock);
        if (a->status != Status::Idle || b->status != Status::Idle
                || !a->backend_quiescent || !b->backend_quiescent)
            throw std::logic_error("device allocation not idle or not quiescent");
        if (a->bytes != expected_bytes || b->bytes != expected_bytes)
            throw std::invalid_argument("DOP padded bytes disagree with allocations");
        if (a->identity.allocation_generation == 0
                || b->identity.allocation_generation == 0
                || a->identity.allocation_generation == b->identity.allocation_generation
                || a->identity.tenant != b->identity.tenant
                || a->identity.device != b->identity.device)
            throw std::invalid_argument("foreign or duplicate GPU allocation identities");
        if ((expected_input && !(a->identity == *expected_input))
                || (expected_output && !(b->identity == *expected_output)))
            throw std::invalid_argument("GPU allocation changed after typed preflight");
        if (a->sequence == std::numeric_limits<std::uint64_t>::max()
                || b->sequence == std::numeric_limits<std::uint64_t>::max())
            throw std::overflow_error("device ticket sequence exhausted");
        // Allocate two fresh, per-submission seals and copy identity strings
        // before changing either allocation. No throwing operations remain.
        auto pair_proof = std::make_shared<const std::uint8_t>(0);
        auto seal_a = std::make_shared<const std::uint8_t>(0);
        auto seal_b = std::make_shared<const std::uint8_t>(0);
        PairTickets tickets{
            a->identity, Ticket(a->identity.allocation_generation, a->sequence + 1, seal_a),
            b->identity, Ticket(b->identity.allocation_generation, b->sequence + 1, seal_b)};
        a->active_ticket_proof = std::move(seal_a);
        b->active_ticket_proof = std::move(seal_b);
        a->active_pair_proof = pair_proof;
        b->active_pair_proof = std::move(pair_proof);
        ++a->sequence;
        ++b->sequence;
        a->backend_quiescent = false;
        b->backend_quiescent = false;
        a->paired = b->paired = true;
        a->status = Status::InFlight;
        b->status = Status::InFlight;
        return tickets;
    }

    static void complete_pair(BufferFence& input, BufferFence& output,
                              const PairTickets& pair, bool succeeded) {
        transition_pair(input, output, pair,
                succeeded ? PairAction::CompleteOk : PairAction::CompleteFailed);
    }

    static void cancel_pair(BufferFence& input, BufferFence& output,
                            const PairTickets& pair) {
        transition_pair(input, output, pair, PairAction::Cancel);
    }

    // Called ONLY after BOTH cancelled device events are verified quiescent.
    static void acknowledge_cancelled_pair(BufferFence& input, BufferFence& output,
                                           const PairTickets& pair) {
        transition_pair(input, output, pair, PairAction::AcknowledgeCancelled);
    }

    Ticket begin_command(const Identity& caller) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (!(state_->identity == caller)) throw std::logic_error("foreign device identity");
        if (state_->status != Status::Idle || state_->paired)
            throw std::logic_error("buffer not idle or belongs to GPU pair");
        if (state_->sequence == std::numeric_limits<std::uint64_t>::max())
            throw std::overflow_error("device ticket sequence exhausted");
        // Allocate the identity before changing state so allocation failure
        // cannot leave a submitted command without a usable ticket.
        auto proof = std::make_shared<const std::uint8_t>(0);
        const auto next = state_->sequence + 1;
        Ticket ticket{state_->identity.allocation_generation, next, proof};
        state_->active_ticket_proof = std::move(proof);
        state_->sequence = next;
        state_->backend_quiescent = false;
        state_->status = Status::InFlight;
        return ticket;
    }

    void complete(Ticket ticket, bool succeeded) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        validate_inflight(ticket);
        state_->status = succeeded ? Status::Idle : Status::Poisoned;
        state_->backend_quiescent = true;
        state_->active_ticket_proof.reset();
    }
    void cancel_pending(Ticket ticket) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        validate_inflight(ticket);
        state_->status = Status::Poisoned;
    }
    void acknowledge_cancelled_device_event(Ticket ticket, const Identity& caller) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (!(state_->identity == caller) || state_->status != Status::Poisoned
            || state_->backend_quiescent || state_->paired
            || ticket.generation_ != state_->identity.allocation_generation
            || ticket.sequence_ != state_->sequence
            || !ticket.proof_
            || ticket.proof_ != state_->active_ticket_proof)
            throw std::logic_error("stale cancelled device fence acknowledgement");
        state_->backend_quiescent = true;
        state_->active_ticket_proof.reset();
    }
    void retire_after_backend_release(const Identity& caller) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (!(state_->identity == caller)) throw std::logic_error("foreign device identity");
        if (state_->status == Status::InFlight || !state_->backend_quiescent)
            throw std::logic_error("device command not yet quiescent");
        state_->status = Status::Closed;
    }
    void close_after_retirement() const {
        std::lock_guard<std::mutex> owner(owner_lock_);
        if (!state_) return;
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->status != Status::Closed)
            throw std::logic_error("backend must release device buffer first");
    }
    Status status() const {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        return state_->status;
    }

    // A per-allocation metadata snapshot, not provider authorization.
    struct Snapshot { Identity identity; std::uint64_t bytes; Status status; };
    Snapshot snapshot() const {
        std::lock_guard<std::mutex> owner_guard(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        return Snapshot{state_->identity, state_->bytes, state_->status};
    }

private:
    enum class PairAction { CompleteOk, CompleteFailed, Cancel, AcknowledgeCancelled };

    static void transition_pair(BufferFence& input, BufferFence& output,
                                const PairTickets& pair, PairAction action) {
        if (&input == &output)
            throw std::invalid_argument("paired GPU owners must differ");
        auto* first = std::less<BufferFence*>{}(&input, &output) ? &input : &output;
        auto* second = first == &input ? &output : &input;
        std::lock_guard<std::mutex> owner_one(first->owner_lock_);
        std::lock_guard<std::mutex> owner_two(second->owner_lock_);
        if (!input.state_ || !output.state_ || input.state_ == output.state_)
            throw std::logic_error("moved or aliased GPU pair owner");
        auto* a = input.state_.get();
        auto* b = output.state_.get();
        auto* low = std::less<State*>{}(a, b) ? a : b;
        auto* high = low == a ? b : a;
        std::lock_guard<std::mutex> state_one(low->lock);
        std::lock_guard<std::mutex> state_two(high->lock);
        // Compare exact submission seals as well as diagnostic integer fields.
        if (!(a->identity == pair.input) || !(b->identity == pair.output)
                || !a->paired || !b->paired
                || !a->active_pair_proof
                || a->active_pair_proof != b->active_pair_proof
                || !a->active_ticket_proof || !b->active_ticket_proof
                || a->active_ticket_proof != pair.input_ticket.proof_
                || b->active_ticket_proof != pair.output_ticket.proof_
                || pair.input_ticket.generation_ != a->identity.allocation_generation
                || pair.output_ticket.generation_ != b->identity.allocation_generation
                || pair.input_ticket.sequence_ != a->sequence
                || pair.output_ticket.sequence_ != b->sequence)
            throw std::logic_error("stale or foreign GPU pair tickets");
        if (action == PairAction::AcknowledgeCancelled) {
            if (a->status != Status::Poisoned || b->status != Status::Poisoned
                    || a->backend_quiescent || b->backend_quiescent)
                throw std::logic_error("device pair has not been cancelled or is partly acknowledged");
            a->backend_quiescent = b->backend_quiescent = true;
            a->paired = b->paired = false;
            a->active_pair_proof.reset();
            b->active_pair_proof.reset();
            a->active_ticket_proof.reset();
            b->active_ticket_proof.reset();
            return;
        }
        if (a->status != Status::InFlight || b->status != Status::InFlight
                || a->backend_quiescent || b->backend_quiescent)
            throw std::logic_error("GPU pair is not jointly in flight");
        if (action == PairAction::Cancel) {
            a->status = b->status = Status::Poisoned;
            return; // seals retained and both physical allocations still live
        }
        a->status = b->status =
                action == PairAction::CompleteOk ? Status::Idle : Status::Poisoned;
        a->backend_quiescent = b->backend_quiescent = true;
        a->paired = b->paired = false;
            a->active_pair_proof.reset();
            b->active_pair_proof.reset();
        a->active_ticket_proof.reset();
        b->active_ticket_proof.reset();
    }

    explicit BufferFence(std::shared_ptr<State> state) : state_(std::move(state)) {}
    // Caller must already hold owner_lock_ to make the state_ access safe.
    void active() const {
        if (!state_) throw std::logic_error("device ownership moved");
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->status == Status::Closed) throw std::logic_error("device buffer closed");
    }
    void validate_inflight(Ticket ticket) const {
        if (state_->status != Status::InFlight || state_->paired ||
            ticket.generation_ != state_->identity.allocation_generation ||
            ticket.sequence_ != state_->sequence ||
            !ticket.proof_ || ticket.proof_ != state_->active_ticket_proof)
            throw std::logic_error("stale or mismatched device event");
    }
    // Protects the *wrapper handle* (not just the shared device state).
    // Lock order: owner_lock_ -> State::lock throughout this class.
    mutable std::mutex owner_lock_;
    std::shared_ptr<State> state_;
};

} // namespace oreslang::gpu
