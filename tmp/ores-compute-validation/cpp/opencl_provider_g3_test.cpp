#include "oreslang/runtime/OpenClProvider.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {

template <typename Exception = std::exception, typename Fn>
void expect_throw(Fn&& fn) {
    bool threw = false;
    try {
        fn();
    } catch (const Exception&) {
        threw = true;
    }
    if (!threw) std::abort();
}

void check(bool value) {
    if (!value) std::abort();
}

struct FakeApi final : oreslang::opencl::Api {
    struct Program {};
    struct Buffer { std::vector<unsigned char> bytes; };
    struct Event { oreslang::opencl::EventStatus status; };

    bool fail_prepare = false;
    int fail_allocate_at = 0;
    int allocation_count = 0;
    bool fail_upload = false;
    bool fail_kernel = false;
    bool fail_download = false;
    bool fail_release_buffer_once = false;
    bool fail_release_event_once = false;
    bool fail_release_program_once = false;
    bool reject_artifact = false;
    bool device_loss_quiescent = false;
    mutable int validated_artifacts = 0;

    int released_programs = 0;
    int released_buffers = 0;
    int released_events = 0;

    std::deque<oreslang::opencl::EventStatus> statuses;
    Event* last_event = nullptr;

    void validate_artifact(
            const oreslang::dop::OpenClArtifact& artifact) const override {
        ++validated_artifacts;
        if (reject_artifact
                || artifact.artifact_abi_version != 1
                || artifact.required_address_bits != 64
                || !artifact.required_float32
                || !artifact.required_little_endian
                || artifact.required_bytes == 0
                || artifact.global_rows == 0
                || artifact.global_fields == 0) {
            throw std::invalid_argument("fake physical artifact rejected");
        }
    }

    Handle prepare(const oreslang::dop::OpenClArtifact&) override {
        if (fail_prepare) throw std::runtime_error("prepare failed");
        return new Program();
    }

    void release_program(Handle program) override {
        if (fail_release_program_once) {
            fail_release_program_once = false;
            throw std::runtime_error("program release failed");
        }
        delete static_cast<Program*>(program);
        ++released_programs;
    }

    Handle allocate(std::uint64_t bytes) override {
        ++allocation_count;
        if (fail_allocate_at != 0 && allocation_count == fail_allocate_at)
            throw std::runtime_error("allocation failed");
        auto* buffer = new Buffer();
        buffer->bytes.assign(static_cast<std::size_t>(bytes), 0);
        return buffer;
    }

    void release_buffer(Handle buffer) override {
        if (fail_release_buffer_once) {
            fail_release_buffer_once = false;
            throw std::runtime_error("release buffer failed");
        }
        delete static_cast<Buffer*>(buffer);
        ++released_buffers;
    }

    Handle enqueue_upload(
            Handle destination,
            const unsigned char* bytes,
            std::size_t length) override {
        if (fail_upload) throw std::runtime_error("upload enqueue failed");
        auto* dst = static_cast<Buffer*>(destination);
        std::copy(bytes, bytes + length, dst->bytes.begin());
        return next_event();
    }

    Handle enqueue_kernel(
            Handle,
            Handle input,
            Handle output,
            std::uint64_t,
            std::uint32_t) override {
        if (fail_kernel) throw std::runtime_error("kernel enqueue failed");
        const auto& src = static_cast<Buffer*>(input)->bytes;
        auto& dst = static_cast<Buffer*>(output)->bytes;
        for (std::size_t i = 0; i < src.size(); ++i)
            dst[i] = static_cast<unsigned char>(src[i] ^ 0x5aU);
        return next_event();
    }

    Handle enqueue_download(
            Handle source,
            unsigned char* bytes,
            std::size_t length) override {
        if (fail_download) throw std::runtime_error("download enqueue failed");
        const auto& src = static_cast<Buffer*>(source)->bytes;
        std::copy(src.begin(), src.begin() + static_cast<std::ptrdiff_t>(length), bytes);
        return next_event();
    }

    oreslang::opencl::EventStatus poll_event(Handle event) override {
        return static_cast<Event*>(event)->status;
    }

    void release_event(Handle event) override {
        if (fail_release_event_once) {
            fail_release_event_once = false;
            throw std::runtime_error("release event failed");
        }
        delete static_cast<Event*>(event);
        ++released_events;
    }

    bool prove_device_loss_quiescence() override {
        return device_loss_quiescent;
    }

    Event* next_event() {
        const auto status = statuses.empty()
                ? oreslang::opencl::EventStatus::Complete
                : pop_status();
        last_event = new Event{status};
        return last_event;
    }

    oreslang::opencl::EventStatus pop_status() {
        const auto status = statuses.front();
        statuses.pop_front();
        return status;
    }
};

struct Fixture {
    oreslang::dop::Plan plan;
    oreslang::dop::Kernel kernel;
    oreslang::gpu::BufferFence input_fence;
    oreslang::gpu::BufferFence output_fence;
    oreslang::dop::DeviceBuffer input;
    oreslang::dop::DeviceBuffer output;
    oreslang::dop::GpuQueueLocation queue;
    std::vector<unsigned char> host;

    Fixture()
        : plan(oreslang::dop::Layout::AoS, 4, 2, 4, 16, 1),
          kernel(plan, oreslang::dop::Element::F32,
                 oreslang::dop::Op::ScaleBias, 1.5f, -2.0f),
          input_fence({"tenant-a", "gpu0", 101}, plan.byte_length()),
          output_fence({"tenant-a", "gpu0", 102}, plan.byte_length()),
          input(plan, input_fence),
          output(plan, output_fence),
          queue{"tenant-a", "gpu0", 9, 21, 4},
          host(static_cast<std::size_t>(plan.byte_length()), 0) {
        for (std::size_t i = 0; i < host.size(); ++i)
            host[i] = static_cast<unsigned char>(i);
    }
};

oreslang::opencl::Provider make_provider(
        FakeApi& api,
        std::uint64_t bytes,
        std::uint64_t resident_multiplier = 4,
        std::string device = "gpu0") {
    return oreslang::opencl::Provider(
            api,
            {"tenant-a", std::move(device), 9, 21, 4},
            {
                bytes * resident_multiplier,
                bytes * 4,
                4,
                4
            },
            {64, bytes * 2, 100, true});
}

} // namespace

int main() {
    using oreslang::gpu::Status;
    using oreslang::opencl::EventStatus;
    using oreslang::opencl::Provider;

    // Successful upload -> kernel -> download -> exact event completion.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);

        check(provider.active_operation_count() == 1);
        check(f.input_fence.status() == Status::InFlight);
        check(f.output_fence.status() == Status::InFlight);
        expect_throw<std::logic_error>([&] { prepared.close(); });

        check(provider.poll(*operation) == Provider::OperationState::KernelSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::DownloadSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::Succeeded);

        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        check(provider.active_operation_count() == 0);

        auto output = operation->output_bytes();
        check(output.size() == f.host.size());
        for (std::size_t i = 0; i < output.size(); ++i)
            check(output[i] == static_cast<unsigned char>(f.host[i] ^ 0x5aU));

        prepared.close();
        check(api.released_programs == 1);
        check(api.released_buffers == 2);
        check(api.released_events == 3);
    }

    // Quota rejection is before fence claim or physical allocation.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length(), 1);
        auto prepared = provider.prepare(f.kernel);
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(api.allocation_count == 0);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }

    // The provider object itself is the trusted queue capability. Binding
    // that capability to another device cannot retarget existing buffer owners.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(
                api, f.plan.byte_length(), 4, "gpu1");
        auto prepared = provider.prepare(f.kernel);
        expect_throw<std::logic_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        prepared.close();
    }

    // Allocation and first-enqueue failures roll back quota and RESERVED fences.
    {
        Fixture f;
        FakeApi api;
        api.fail_allocate_at = 2;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }
    {
        Fixture f;
        FakeApi api;
        api.fail_upload = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }

    // A post-submit enqueue failure cannot use pre-enqueue abort.
    {
        Fixture f;
        FakeApi api;
        api.fail_kernel = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);

        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        check(f.input_fence.status() == Status::Poisoned);
        check(f.output_fence.status() == Status::Poisoned);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});

        f.input_fence.retire_after_backend_release({"tenant-a", "gpu0", 101});
        f.output_fence.retire_after_backend_release({"tenant-a", "gpu0", 102});
        prepared.close();
    }

    // Timeout/cancel holds resources until the exact device event is terminal.
    {
        Fixture f;
        FakeApi api;
        api.statuses.push_back(EventStatus::Pending);
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);

        provider.request_timeout_cancellation(*operation);
        const auto before = provider.usage();
        check(before.resident_bytes > 0);
        check(provider.poll(*operation) == Provider::OperationState::CancelRequested);
        check(provider.usage() == before);
        check(operation->timeout_requested());

        api.last_event->status = EventStatus::Complete;
        check(provider.poll(*operation) == Provider::OperationState::Cancelled);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        check(f.input_fence.status() == Status::Poisoned);
        check(f.output_fence.status() == Status::Poisoned);

        f.input_fence.retire_after_backend_release({"tenant-a", "gpu0", 101});
        f.output_fence.retire_after_backend_release({"tenant-a", "gpu0", 102});
        prepared.close();
    }

    // Device loss poisons but does not imply physical quiescence.
    {
        Fixture f;
        FakeApi api;
        api.statuses.push_back(EventStatus::DeviceLost);
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);

        check(provider.poll(*operation) == Provider::OperationState::DeviceLost);
        check(f.input_fence.status() == Status::Poisoned);
        check(f.output_fence.status() == Status::Poisoned);
        check(provider.usage().resident_bytes > 0);
        check(provider.active_operation_count() == 1);
        expect_throw<std::logic_error>([&] { (void)operation->output_bytes(); });
        expect_throw<std::logic_error>([&] { prepared.close(); });
        expect_throw<std::logic_error>([&] {
            provider.recover_device_loss(*operation);
        });
        check(provider.usage().resident_bytes > 0);
        expect_throw<std::logic_error>([&] {
            f.input_fence.retire_after_backend_release(
                    {"tenant-a", "gpu0", 101});
        });

        api.device_loss_quiescent = true;
        provider.recover_device_loss(*operation);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        check(provider.active_operation_count() == 0);
        f.input_fence.retire_after_backend_release(
                {"tenant-a", "gpu0", 101});
        f.output_fence.retire_after_backend_release(
                {"tenant-a", "gpu0", 102});
        prepared.close();
    }

    // Failed physical release retains quota/program pin until explicit retry.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);
        (void)provider.poll(*operation);
        (void)provider.poll(*operation);
        api.fail_release_buffer_once = true;

        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        check(operation->state() == Provider::OperationState::ReleaseFailed);
        check(provider.usage().resident_bytes > 0);
        expect_throw<std::logic_error>([&] { prepared.close(); });

        provider.retry_release(*operation);
        check(operation->state() == Provider::OperationState::Succeeded);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        (void)operation->output_bytes();
        prepared.close();
    }

    // Failed pre-enqueue physical cleanup retains the exact resource
    // authority and quota until an explicit retry succeeds.
    {
        Fixture f;
        FakeApi api;
        api.fail_upload = true;
        api.fail_release_buffer_once = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);

        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage().resident_bytes > 0);
        expect_throw<std::logic_error>([&] { prepared.close(); });
        expect_throw<std::logic_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.host);
        });

        provider.retry_pre_enqueue_cleanup();
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }

    // Failed prepared-program release keeps the same program handle retryable.
    {
        Fixture f;
        FakeApi api;
        api.fail_release_program_once = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        expect_throw<std::runtime_error>([&] { prepared.close(); });
        check(api.released_programs == 0);
        prepared.close();
        check(api.released_programs == 1);
    }

    // Provider retains a submitted operation even if the caller drops its handle.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);
        std::weak_ptr<Provider::Operation> weak = operation;
        operation.reset();
        check(!weak.expired());
        check(provider.active_operation_count() == 1);

        auto retained = weak.lock();
        check(provider.poll(*retained) == Provider::OperationState::KernelSubmitted);
        check(provider.poll(*retained) == Provider::OperationState::DownloadSubmitted);
        check(provider.poll(*retained) == Provider::OperationState::Succeeded);
        check(provider.active_operation_count() == 0);
        prepared.close();
    }

    // Physical artifact validation happens before driver preparation.
    {
        Fixture f;
        FakeApi api;
        api.reject_artifact = true;
        auto provider = make_provider(api, f.plan.byte_length());
        expect_throw<std::invalid_argument>([&] {
            (void)provider.prepare(f.kernel);
        });
        check(api.validated_artifacts == 1);
        check(api.released_programs == 0);
    }

    // Provider queue/target profiles are closed before any physical launch.
    {
        Fixture f;
        FakeApi api;
        expect_throw<std::invalid_argument>([&] {
            oreslang::opencl::Provider invalid(
                    api,
                    {"tenant-a", "gpu0", 9, 21, 3},
                    {1024, 1024, 1, 1},
                    {64, 1024, 100, true});
        });
        expect_throw<std::invalid_argument>([&] {
            oreslang::opencl::Provider invalid(
                    api,
                    {"tenant-a", "gpu0", 9, 21, 4},
                    {1024, 1024, 1, 1},
                    {32, 1024, 100, true});
        });
    }

    // Terminal event release failure keeps the exact event resumable.
    {
        Fixture f;
        FakeApi api;
        api.fail_release_event_once = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel);
        auto operation = provider.begin(
                prepared, f.input, f.output, f.host);

        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        check(provider.poll(*operation) == Provider::OperationState::KernelSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::DownloadSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::Succeeded);
        prepared.close();
    }

    std::cout << "trusted OpenCL G3 provider state-machine tests passed\n";
    return 0;
}
