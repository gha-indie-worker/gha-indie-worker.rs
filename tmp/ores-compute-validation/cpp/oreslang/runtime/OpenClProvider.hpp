#pragma once

#include "oreslang/runtime/DopGpuAdmission.hpp"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace oreslang::opencl {

enum class EventStatus {
    Pending,
    Complete,
    Failed,
    DeviceLost
};

class Api {
public:
    using Handle = void*;

    virtual ~Api() = default;

    virtual void validate_artifact(const dop::OpenClArtifact& artifact) const = 0;
    virtual Handle prepare(const dop::OpenClArtifact& artifact) = 0;
    virtual void release_program(Handle program) = 0;

    virtual Handle allocate(std::uint64_t bytes) = 0;
    virtual void release_buffer(Handle buffer) = 0;

    virtual Handle enqueue_upload(
            Handle destination,
            const unsigned char* bytes,
            std::size_t length) = 0;
    virtual Handle enqueue_kernel(
            Handle program,
            Handle input,
            Handle output,
            std::uint64_t global_rows,
            std::uint32_t global_fields) = 0;
    virtual Handle enqueue_download(
            Handle source,
            unsigned char* bytes,
            std::size_t length) = 0;

    virtual EventStatus poll_event(Handle event) = 0;
    virtual void release_event(Handle event) = 0;
    virtual bool prove_device_loss_quiescence() = 0;
};

struct ProviderIdentity {
    std::string tenant;
    std::string device;
    std::uint64_t queue_id;
    std::uint64_t queue_generation;
    std::uint32_t queue_minimum_alignment;
};

struct Limits {
    std::uint64_t resident_bytes;
    std::uint64_t pinned_host_bytes;
    std::uint32_t outstanding_events;
    std::uint32_t queue_slots;
};

struct Usage {
    std::uint64_t resident_bytes;
    std::uint64_t pinned_host_bytes;
    std::uint32_t outstanding_events;
    std::uint32_t queue_slots;

    friend bool operator==(const Usage& left, const Usage& right) noexcept {
        return left.resident_bytes == right.resident_bytes
            && left.pinned_host_bytes == right.pinned_host_bytes
            && left.outstanding_events == right.outstanding_events
            && left.queue_slots == right.queue_slots;
    }
};

class Provider final {
    struct ProgramLease;

    class QuotaLease {
    public:
        QuotaLease() = default;
        QuotaLease(
                Provider& owner,
                std::uint64_t resident,
                std::uint64_t pinned,
                std::uint32_t events,
                std::uint32_t queues)
            : owner_(&owner),
              resident_(resident),
              pinned_(pinned),
              events_(events),
              queues_(queues) {}

        QuotaLease(const QuotaLease&) = delete;
        QuotaLease& operator=(const QuotaLease&) = delete;

        QuotaLease(QuotaLease&& other) noexcept
            : owner_(std::exchange(other.owner_, nullptr)),
              resident_(other.resident_),
              pinned_(other.pinned_),
              events_(other.events_),
              queues_(other.queues_),
              released_(other.released_) {}

        QuotaLease& operator=(QuotaLease&&) = delete;
        ~QuotaLease() = default;

        void release();

    private:
        friend class Provider;
        Provider* owner_ = nullptr;
        std::uint64_t resident_ = 0;
        std::uint64_t pinned_ = 0;
        std::uint32_t events_ = 0;
        std::uint32_t queues_ = 0;
        bool released_ = false;
    };

    struct PreEnqueueRecovery {
        explicit PreEnqueueRecovery(QuotaLease lease)
            : lease_(std::move(lease)) {}

        Api::Handle input_physical_ = nullptr;
        Api::Handle output_physical_ = nullptr;
        QuotaLease lease_;
    };

public:
    enum class OperationState {
        Reserved,
        UploadSubmitted,
        KernelSubmitted,
        DownloadSubmitted,
        CancelRequested,
        Succeeded,
        Failed,
        Cancelled,
        DeviceLost,
        ReleaseFailed
    };

    class PreparedKernel final {
    public:
        PreparedKernel(const PreparedKernel&) = delete;
        PreparedKernel& operator=(const PreparedKernel&) = delete;
        PreparedKernel(PreparedKernel&&) noexcept = default;
        PreparedKernel& operator=(PreparedKernel&&) = delete;

        const dop::Kernel& kernel() const {
            active();
            return kernel_;
        }

        dop::GpuTarget target() const {
            active();
            return target_;
        }

        const dop::OpenClArtifact& artifact() const {
            active();
            return artifact_;
        }

        void close() {
            active();
            if (lease_.use_count() != 1)
                throw std::logic_error(
                        "prepared OpenCL kernel still has active operations");
            lease_->release_explicit();
            lease_.reset();
        }

    private:
        friend class Provider;

        PreparedKernel(
                dop::Kernel kernel,
                dop::GpuTarget target,
                dop::OpenClArtifact artifact,
                std::shared_ptr<ProgramLease> lease)
            : kernel_(std::move(kernel)),
              target_(target),
              artifact_(std::move(artifact)),
              lease_(std::move(lease)) {}

        void active() const {
            if (!lease_)
                throw std::logic_error("prepared OpenCL kernel already closed");
        }

        dop::Kernel kernel_;
        dop::GpuTarget target_;
        dop::OpenClArtifact artifact_;
        std::shared_ptr<ProgramLease> lease_;
    };

    class Operation final {
    public:
        Operation(const Operation&) = delete;
        Operation& operator=(const Operation&) = delete;
        Operation(Operation&&) = delete;
        Operation& operator=(Operation&&) = delete;

        OperationState state() const {
            std::lock_guard<std::mutex> guard(lock_);
            return state_;
        }

        bool timeout_requested() const {
            std::lock_guard<std::mutex> guard(lock_);
            return timeout_requested_;
        }

        std::vector<unsigned char> output_bytes() const {
            std::lock_guard<std::mutex> guard(lock_);
            if (state_ != OperationState::Succeeded || !cleanup_complete_)
                throw std::logic_error("OpenCL provider output is not ready");
            return output_staging_;
        }

    private:
        friend class Provider;

        Operation(
                Provider& owner,
                std::shared_ptr<ProgramLease> program,
                dop::DeviceBuffer input,
                dop::DeviceBuffer output,
                dop::QueueClaimedGpuBinding claim,
                QuotaLease lease,
                std::vector<unsigned char> input_staging,
                std::vector<unsigned char> output_staging,
                Api::Handle input_physical,
                Api::Handle output_physical,
                Api::Handle event)
            : owner_(&owner),
              program_(std::move(program)),
              input_(std::move(input)),
              output_(std::move(output)),
              claim_(std::move(claim)),
              lease_(std::move(lease)),
              input_staging_(std::move(input_staging)),
              output_staging_(std::move(output_staging)),
              input_physical_(input_physical),
              output_physical_(output_physical),
              event_(event),
              state_(event ? OperationState::UploadSubmitted
                           : OperationState::Reserved) {}

        Provider* owner_;
        std::shared_ptr<ProgramLease> program_;
        dop::DeviceBuffer input_;
        dop::DeviceBuffer output_;
        dop::QueueClaimedGpuBinding claim_;
        QuotaLease lease_;
        std::vector<unsigned char> input_staging_;
        std::vector<unsigned char> output_staging_;
        Api::Handle input_physical_ = nullptr;
        Api::Handle output_physical_ = nullptr;
        Api::Handle event_ = nullptr;
        mutable std::mutex lock_;
        OperationState state_;
        bool cancel_requested_ = false;
        bool timeout_requested_ = false;
        bool cleanup_complete_ = false;
        bool has_release_resume_state_ = false;
        OperationState release_resume_state_ = OperationState::Failed;
    };

    Provider(
            Api& api,
            ProviderIdentity identity,
            Limits limits,
            dop::GpuTarget target)
        : api_(api),
          identity_(std::move(identity)),
          limits_(limits),
          target_(target) {
        if (identity_.tenant.empty()
                || identity_.device.empty()
                || identity_.queue_id == 0
                || identity_.queue_generation == 0
                || identity_.queue_minimum_alignment == 0
                || (identity_.queue_minimum_alignment
                    & (identity_.queue_minimum_alignment - 1)) != 0)
            throw std::invalid_argument("OpenCL provider identity incomplete");
        if (limits_.resident_bytes == 0
                || limits_.pinned_host_bytes == 0
                || limits_.outstanding_events == 0
                || limits_.queue_slots == 0)
            throw std::invalid_argument("OpenCL provider quotas must be positive");
        if (limits_.resident_bytes
                > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())
                || limits_.pinned_host_bytes
                > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
            throw std::invalid_argument("OpenCL provider quotas exceed signed i64 ABI");
        if (target_.address_bits != 64
                || !target_.float32
                || target_.maximum_buffer_bytes == 0
                || target_.maximum_rows == 0)
            throw std::invalid_argument(
                    "OpenCL provider target incompatible with portable G3 ABI");

        active_operations_.reserve(limits_.queue_slots);
        quarantined_operations_.reserve(limits_.queue_slots);
        pre_enqueue_recoveries_.reserve(limits_.queue_slots);
        pre_enqueue_operation_recoveries_.reserve(limits_.queue_slots);
    }

    PreparedKernel prepare(const dop::Kernel& kernel) {
        auto artifact = dop::lower_opencl(kernel, target_);
        api_.validate_artifact(artifact);
        Api::Handle program = api_.prepare(artifact);
        if (!program)
            throw std::runtime_error("OpenCL provider returned null program");
        auto lease = std::make_shared<ProgramLease>(api_, program);
        return PreparedKernel(kernel, target_, std::move(artifact), std::move(lease));
    }

    std::shared_ptr<Operation> begin(
            PreparedKernel& prepared,
            const dop::DeviceBuffer& input,
            const dop::DeviceBuffer& output,
            const std::vector<unsigned char>& host_input) {
        prepared.active();
        const dop::GpuQueueLocation queue{
                identity_.tenant,
                identity_.device,
                identity_.queue_id,
                identity_.queue_generation,
                identity_.queue_minimum_alignment};
        {
            std::lock_guard<std::mutex> quarantine_guard(quarantine_lock_);
            if (quarantine_latched_
                    || !quarantined_operations_.empty()
                    || !pre_enqueue_recoveries_.empty()
                    || !pre_enqueue_operation_recoveries_.empty())
                throw std::logic_error(
                        "OpenCL provider quarantined after post-enqueue invariant failure");
        }

        const auto& artifact = prepared.artifact();
        const auto bytes = artifact.required_bytes;
        if (bytes == 0
                || bytes > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())
                || host_input.size() != static_cast<std::size_t>(bytes))
            throw std::invalid_argument("OpenCL host bytes do not match admitted kernel");

        const auto resident = checked_mul(bytes, 2);
        const auto pinned = checked_mul(bytes, 2);
        auto lease = reserve(resident, pinned, 1, 1);
        auto early_recovery = std::make_unique<PreEnqueueRecovery>(
                std::move(lease));

        Api::Handle input_physical = nullptr;
        Api::Handle output_physical = nullptr;
        std::unique_ptr<dop::QueueClaimedGpuBinding> claim;
        std::shared_ptr<Operation> operation;
        bool durable_enqueued = false;

        try {
            input_physical = api_.allocate(bytes);
            if (!input_physical)
                throw std::runtime_error("OpenCL provider returned null input allocation");
            output_physical = api_.allocate(bytes);
            if (!output_physical)
                throw std::runtime_error("OpenCL provider returned null output allocation");

            claim = std::make_unique<dop::QueueClaimedGpuBinding>(
                    dop::claim_gpu_for_queue(
                            prepared.kernel(),
                            prepared.target(),
                            input,
                            output,
                            queue));

            std::vector<unsigned char> input_staging(host_input);
            std::vector<unsigned char> output_staging(host_input.size(), 0);

            // Allocate the complete resumable operation before the first
            // physical enqueue. After enqueue returns, no allocation is
            // required to preserve ownership of the accepted command.
            operation = std::shared_ptr<Operation>(new Operation(
                    *this,
                    prepared.lease_,
                    input,
                    output,
                    std::move(*claim),
                    std::move(early_recovery->lease_),
                    std::move(input_staging),
                    std::move(output_staging),
                    input_physical,
                    output_physical,
                    nullptr));
            claim.reset();
            register_active(operation);

            Api::Handle upload = api_.enqueue_upload(
                    input_physical,
                    operation->input_staging_.data(),
                    operation->input_staging_.size());
            if (!upload)
                throw std::runtime_error("OpenCL provider returned null upload event");
            operation->event_ = upload;
            durable_enqueued = true;

            // Returning a provider-owned event is the durable enqueue witness.
            // mark_gpu_submitted is an allocation-free host-ledger transition.
            dop::mark_gpu_submitted(
                    operation->input_, operation->output_, operation->claim_);
            operation->state_ = OperationState::UploadSubmitted;
            return operation;
        } catch (...) {
            auto failure = std::current_exception();

            if (durable_enqueued && operation) {
                std::lock_guard<std::mutex> quarantine_guard(quarantine_lock_);
                quarantined_operations_.push_back(operation);
                std::rethrow_exception(failure);
            }

            try {
                if (operation) {
                    dop::abort_gpu_before_enqueue(
                            operation->input_,
                            operation->output_,
                            operation->claim_);
                } else if (claim) {
                    dop::abort_gpu_before_enqueue(input, output, *claim);
                }
            } catch (...) {
                std::lock_guard<std::mutex> quarantine_guard(quarantine_lock_);
                quarantine_latched_ = true;
            }

            if (operation) {
                try {
                    finish_cleanup(*operation);
                } catch (...) {
                    std::lock_guard<std::mutex> recovery_guard(quarantine_lock_);
                    if (pre_enqueue_operation_recoveries_.size()
                            >= limits_.queue_slots)
                        throw std::logic_error(
                                "OpenCL pre-enqueue operation recovery capacity exhausted");
                    pre_enqueue_operation_recoveries_.push_back(operation);
                }
            } else {
                if (input_physical) {
                    try {
                        api_.release_buffer(input_physical);
                        input_physical = nullptr;
                    } catch (...) {
                        early_recovery->input_physical_ = input_physical;
                    }
                }
                if (output_physical) {
                    try {
                        api_.release_buffer(output_physical);
                        output_physical = nullptr;
                    } catch (...) {
                        early_recovery->output_physical_ = output_physical;
                    }
                }

                if (!early_recovery->input_physical_
                        && !early_recovery->output_physical_) {
                    early_recovery->lease_.release();
                } else {
                    std::lock_guard<std::mutex> recovery_guard(quarantine_lock_);
                    if (pre_enqueue_recoveries_.size() >= limits_.queue_slots)
                        throw std::logic_error(
                                "OpenCL pre-enqueue recovery capacity exhausted");
                    pre_enqueue_recoveries_.push_back(
                            std::move(early_recovery));
                }
            }
            std::rethrow_exception(failure);
        }
    }

    OperationState poll(Operation& operation) {
        std::lock_guard<std::mutex> guard(operation.lock_);
        require_owner(operation);
        if (terminal(operation.state_))
            return operation.state_;
        if (operation.state_ == OperationState::Reserved)
            throw std::logic_error(
                    "reserved OpenCL operation was never physically submitted");

        const auto status = api_.poll_event(operation.event_);
        if (status == EventStatus::Pending)
            return operation.state_;

        Api::Handle completed_event = operation.event_;
        api_.release_event(completed_event);
        operation.event_ = nullptr;

        if (operation.cancel_requested_) {
            gpu::BufferFence::acknowledge_cancelled_pair(
                    operation.input_.fence,
                    operation.output_.fence,
                    operation.claim_.claim().tickets);
            operation.state_ = OperationState::Cancelled;
            finish_cleanup(operation);
            return operation.state_;
        }

        if (status == EventStatus::DeviceLost) {
            gpu::BufferFence::cancel_pair(
                    operation.input_.fence,
                    operation.output_.fence,
                    operation.claim_.claim().tickets);
            operation.cancel_requested_ = true;
            operation.state_ = OperationState::DeviceLost;
            retain_quarantined(operation);
            return operation.state_;
        }

        if (status == EventStatus::Failed) {
            gpu::BufferFence::complete_pair(
                    operation.input_.fence,
                    operation.output_.fence,
                    operation.claim_.claim().tickets,
                    false);
            operation.state_ = OperationState::Failed;
            finish_cleanup(operation);
            return operation.state_;
        }

        try {
            switch (operation.state_) {
                case OperationState::UploadSubmitted: {
                    operation.event_ = api_.enqueue_kernel(
                            operation.program_->program(),
                            operation.input_physical_,
                            operation.output_physical_,
                            operation.claim_.claim().binding.artifact.global_rows,
                            operation.claim_.claim().binding.artifact.global_fields);
                    if (!operation.event_)
                        throw std::runtime_error("OpenCL provider returned null kernel event");
                    operation.state_ = OperationState::KernelSubmitted;
                    break;
                }
                case OperationState::KernelSubmitted: {
                    operation.event_ = api_.enqueue_download(
                            operation.output_physical_,
                            operation.output_staging_.data(),
                            operation.output_staging_.size());
                    if (!operation.event_)
                        throw std::runtime_error("OpenCL provider returned null download event");
                    operation.state_ = OperationState::DownloadSubmitted;
                    break;
                }
                case OperationState::DownloadSubmitted: {
                    gpu::BufferFence::complete_pair(
                            operation.input_.fence,
                            operation.output_.fence,
                            operation.claim_.claim().tickets,
                            true);
                    operation.state_ = OperationState::Succeeded;
                    finish_cleanup(operation);
                    break;
                }
                default:
                    throw std::logic_error("invalid OpenCL provider operation transition");
            }
        } catch (...) {
            auto failure = std::current_exception();

            if (operation.state_ == OperationState::UploadSubmitted
                    || operation.state_ == OperationState::KernelSubmitted) {
                try {
                    gpu::BufferFence::complete_pair(
                            operation.input_.fence,
                            operation.output_.fence,
                            operation.claim_.claim().tickets,
                            false);
                } catch (...) {
                    std::lock_guard<std::mutex> quarantine_guard(quarantine_lock_);
                    quarantine_latched_ = true;
                    std::rethrow_exception(failure);
                }
                operation.state_ = OperationState::Failed;
                try { finish_cleanup(operation); }
                catch (...) { }
                std::rethrow_exception(failure);
            }

            if (operation.state_ == OperationState::DownloadSubmitted) {
                std::lock_guard<std::mutex> quarantine_guard(quarantine_lock_);
                quarantine_latched_ = true;
            }
            // Succeeded/ReleaseFailed means host fences already terminalized;
            // only physical cleanup failed, so never replay pair completion.
            std::rethrow_exception(failure);
        }

        return operation.state_;
    }

    void request_cancel(Operation& operation) {
        request_cancel(operation, false);
    }

    void request_timeout_cancellation(Operation& operation) {
        request_cancel(operation, true);
    }

    void retry_release(Operation& operation) {
        std::lock_guard<std::mutex> guard(operation.lock_);
        require_owner(operation);
        if (operation.state_ != OperationState::ReleaseFailed)
            throw std::logic_error("OpenCL operation has no failed physical release");
        finish_cleanup(operation);
        if (operation.cleanup_complete_
                && operation.state_ == OperationState::DeviceLost)
            remove_quarantined(operation);
    }

    void recover_device_loss(Operation& operation) {
        std::lock_guard<std::mutex> guard(operation.lock_);
        require_owner(operation);
        if (operation.state_ != OperationState::DeviceLost)
            throw std::logic_error(
                    "OpenCL operation is not awaiting device-loss recovery");
        if (!api_.prove_device_loss_quiescence())
            throw std::logic_error(
                    "OpenCL provider could not prove device-loss quiescence");

        gpu::BufferFence::acknowledge_cancelled_pair(
                operation.input_.fence,
                operation.output_.fence,
                operation.claim_.claim().tickets);
        finish_cleanup(operation);
        remove_quarantined(operation);
    }

    void retry_pre_enqueue_cleanup() {
        std::shared_ptr<Operation> operation_recovery;
        {
            std::lock_guard<std::mutex> recovery_guard(quarantine_lock_);
            if (!pre_enqueue_operation_recoveries_.empty())
                operation_recovery = pre_enqueue_operation_recoveries_.front();
        }
        if (operation_recovery) {
            finish_cleanup(*operation_recovery);
            std::lock_guard<std::mutex> recovery_guard(quarantine_lock_);
            pre_enqueue_operation_recoveries_.erase(
                    std::remove(
                            pre_enqueue_operation_recoveries_.begin(),
                            pre_enqueue_operation_recoveries_.end(),
                            operation_recovery),
                    pre_enqueue_operation_recoveries_.end());
            return;
        }

        std::lock_guard<std::mutex> recovery_guard(quarantine_lock_);
        if (pre_enqueue_recoveries_.empty())
            throw std::logic_error(
                    "OpenCL provider has no pre-enqueue recovery");

        auto& recovery = pre_enqueue_recoveries_.front();
        std::exception_ptr failure;
        if (recovery->input_physical_) {
            try {
                api_.release_buffer(recovery->input_physical_);
                recovery->input_physical_ = nullptr;
            } catch (...) {
                failure = std::current_exception();
            }
        }
        if (recovery->output_physical_) {
            try {
                api_.release_buffer(recovery->output_physical_);
                recovery->output_physical_ = nullptr;
            } catch (...) {
                if (!failure) failure = std::current_exception();
            }
        }
        if (failure)
            std::rethrow_exception(failure);

        recovery->lease_.release();
        pre_enqueue_recoveries_.erase(pre_enqueue_recoveries_.begin());
    }

    Usage usage() const {
        std::lock_guard<std::mutex> guard(quota_lock_);
        return Usage{
                resident_bytes_,
                pinned_host_bytes_,
                outstanding_events_,
                queue_slots_};
    }

    std::size_t active_operation_count() const {
        std::lock_guard<std::mutex> guard(quarantine_lock_);
        return active_operations_.size();
    }

private:
    struct ProgramLease {
        Api* api;
        Api::Handle handle;
        mutable std::mutex lock;
        bool released = false;

        ProgramLease(Api& value, Api::Handle program)
            : api(&value), handle(program) {}

        ~ProgramLease() {
            std::lock_guard<std::mutex> guard(lock);
            if (!released && handle) {
                try { api->release_program(handle); }
                catch (...) { }
            }
        }

        Api::Handle program() const {
            std::lock_guard<std::mutex> guard(lock);
            if (released || !handle)
                throw std::logic_error("prepared OpenCL program already released");
            return handle;
        }

        void release_explicit() {
            std::lock_guard<std::mutex> guard(lock);
            if (released || !handle)
                throw std::logic_error("prepared OpenCL program already released");
            api->release_program(handle);
            released = true;
            handle = nullptr;
        }
    };

    QuotaLease reserve(
            std::uint64_t resident,
            std::uint64_t pinned,
            std::uint32_t events,
            std::uint32_t queues) {
        std::lock_guard<std::mutex> guard(quota_lock_);
        if (resident > limits_.resident_bytes - resident_bytes_
                || pinned > limits_.pinned_host_bytes - pinned_host_bytes_
                || events > limits_.outstanding_events - outstanding_events_
                || queues > limits_.queue_slots - queue_slots_)
            throw std::runtime_error("OpenCL provider quota exhausted");

        resident_bytes_ += resident;
        pinned_host_bytes_ += pinned;
        outstanding_events_ += events;
        queue_slots_ += queues;
        return QuotaLease(*this, resident, pinned, events, queues);
    }

    void release_quota(QuotaLease& lease) {
        std::lock_guard<std::mutex> guard(quota_lock_);
        if (resident_bytes_ < lease.resident_
                || pinned_host_bytes_ < lease.pinned_
                || outstanding_events_ < lease.events_
                || queue_slots_ < lease.queues_)
            throw std::logic_error("OpenCL provider quota accounting underflow");

        resident_bytes_ -= lease.resident_;
        pinned_host_bytes_ -= lease.pinned_;
        outstanding_events_ -= lease.events_;
        queue_slots_ -= lease.queues_;
    }

    void register_active(const std::shared_ptr<Operation>& operation) {
        std::lock_guard<std::mutex> guard(quarantine_lock_);
        if (active_operations_.size() >= limits_.queue_slots)
            throw std::logic_error(
                    "OpenCL active-operation registry exhausted");
        active_operations_.push_back(operation);
    }

    void remove_active(Operation& operation) {
        std::lock_guard<std::mutex> guard(quarantine_lock_);
        active_operations_.erase(
                std::remove_if(
                        active_operations_.begin(),
                        active_operations_.end(),
                        [&](const std::shared_ptr<Operation>& candidate) {
                            return candidate.get() == &operation;
                        }),
                active_operations_.end());
    }

    void retain_quarantined(Operation& operation) {
        std::lock_guard<std::mutex> guard(quarantine_lock_);
        const auto existing = std::find_if(
                quarantined_operations_.begin(),
                quarantined_operations_.end(),
                [&](const std::shared_ptr<Operation>& candidate) {
                    return candidate.get() == &operation;
                });
        if (existing != quarantined_operations_.end())
            return;
        const auto active = std::find_if(
                active_operations_.begin(),
                active_operations_.end(),
                [&](const std::shared_ptr<Operation>& candidate) {
                    return candidate.get() == &operation;
                });
        if (active == active_operations_.end())
            throw std::logic_error(
                    "cannot quarantine unregistered OpenCL operation");
        quarantined_operations_.push_back(*active);
    }

    void remove_quarantined(Operation& operation) {
        std::lock_guard<std::mutex> guard(quarantine_lock_);
        quarantined_operations_.erase(
                std::remove_if(
                        quarantined_operations_.begin(),
                        quarantined_operations_.end(),
                        [&](const std::shared_ptr<Operation>& candidate) {
                            return candidate.get() == &operation;
                        }),
                quarantined_operations_.end());
    }

    void request_cancel(Operation& operation, bool timeout) {
        std::lock_guard<std::mutex> guard(operation.lock_);
        require_owner(operation);
        if (terminal(operation.state_))
            throw std::logic_error("OpenCL operation already terminal");

        if (!operation.cancel_requested_) {
            gpu::BufferFence::cancel_pair(
                    operation.input_.fence,
                    operation.output_.fence,
                    operation.claim_.claim().tickets);
            operation.cancel_requested_ = true;
            operation.state_ = OperationState::CancelRequested;
        }
        operation.timeout_requested_ = operation.timeout_requested_ || timeout;
    }

    void finish_cleanup(Operation& operation) {
        std::exception_ptr failure;

        if (operation.input_physical_) {
            try {
                api_.release_buffer(operation.input_physical_);
                operation.input_physical_ = nullptr;
            } catch (...) {
                failure = std::current_exception();
            }
        }

        if (operation.output_physical_) {
            try {
                api_.release_buffer(operation.output_physical_);
                operation.output_physical_ = nullptr;
            } catch (...) {
                if (!failure) failure = std::current_exception();
            }
        }

        if (failure) {
            if (operation.state_ != OperationState::ReleaseFailed) {
                operation.release_resume_state_ = operation.state_;
                operation.has_release_resume_state_ = true;
            }
            operation.state_ = OperationState::ReleaseFailed;
            std::rethrow_exception(failure);
        }

        if (!operation.cleanup_complete_) {
            operation.lease_.release();
            operation.program_.reset();
            operation.cleanup_complete_ = true;
            remove_active(operation);
        }

        if (operation.state_ == OperationState::ReleaseFailed) {
            if (!operation.has_release_resume_state_)
                throw std::logic_error("missing OpenCL release-resume state");
            operation.state_ = operation.release_resume_state_;
            operation.has_release_resume_state_ = false;
        }
    }

    void require_owner(const Operation& operation) const {
        if (operation.owner_ != this)
            throw std::invalid_argument("foreign OpenCL provider operation");
    }

    static bool terminal(OperationState state) {
        switch (state) {
            case OperationState::Succeeded:
            case OperationState::Failed:
            case OperationState::Cancelled:
            case OperationState::DeviceLost:
            case OperationState::ReleaseFailed:
                return true;
            default:
                return false;
        }
    }

    static std::uint64_t checked_mul(
            std::uint64_t left,
            std::uint64_t right) {
        if (left != 0
                && right > std::numeric_limits<std::uint64_t>::max() / left)
            throw std::overflow_error("OpenCL provider quota arithmetic overflow");
        const auto value = left * right;
        if (value > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
            throw std::overflow_error("OpenCL provider quota exceeds signed i64 ABI");
        return value;
    }

    Api& api_;
    ProviderIdentity identity_;
    Limits limits_;
    dop::GpuTarget target_;

    mutable std::mutex quota_lock_;
    mutable std::mutex quarantine_lock_;
    std::vector<std::shared_ptr<Operation>> active_operations_;
    std::vector<std::shared_ptr<Operation>> quarantined_operations_;
    std::vector<std::unique_ptr<PreEnqueueRecovery>> pre_enqueue_recoveries_;
    std::vector<std::shared_ptr<Operation>> pre_enqueue_operation_recoveries_;
    bool quarantine_latched_ = false;
    std::uint64_t resident_bytes_ = 0;
    std::uint64_t pinned_host_bytes_ = 0;
    std::uint32_t outstanding_events_ = 0;
    std::uint32_t queue_slots_ = 0;
};

inline void Provider::QuotaLease::release() {
    if (!owner_ || released_)
        throw std::logic_error("OpenCL quota lease already released");
    owner_->release_quota(*this);
    released_ = true;
}

} // namespace oreslang::opencl
