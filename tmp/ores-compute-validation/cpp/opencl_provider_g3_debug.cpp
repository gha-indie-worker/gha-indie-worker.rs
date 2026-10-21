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

    int released_programs = 0;
    int released_buffers = 0;
    int released_events = 0;

    std::deque<oreslang::opencl::EventStatus> statuses;
    Event* last_event = nullptr;

    Handle prepare(const oreslang::dop::OpenClArtifact&) override {
        if (fail_prepare) throw std::runtime_error("prepare failed");
        return new Program();
    }

    void release_program(Handle program) override {
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
        std::uint64_t resident_multiplier = 4) {
    return oreslang::opencl::Provider(
            api,
            {"tenant-a", "gpu0", 9, 21},
            {
                bytes * resident_multiplier,
                bytes * 4,
                4,
                4
            });
}

oreslang::dop::GpuTarget target(const Fixture& fixture) {
    return {64, fixture.plan.byte_length() * 2, 100, true};
}

} // namespace

int main() {
    using oreslang::gpu::Status;
    using oreslang::opencl::EventStatus;
    using oreslang::opencl::Provider;

    std::cerr << "CASE success" << std::endl;
    // Successful upload -> kernel -> download -> exact event completion.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);

        check(f.input_fence.status() == Status::InFlight);
        check(f.output_fence.status() == Status::InFlight);
        expect_throw<std::logic_error>([&] { prepared.close(); });

        check(provider.poll(*operation) == Provider::OperationState::KernelSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::DownloadSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::Succeeded);

        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});

        auto output = operation->output_bytes();
        check(output.size() == f.host.size());
        for (std::size_t i = 0; i < output.size(); ++i)
            check(output[i] == static_cast<unsigned char>(f.host[i] ^ 0x5aU));

        prepared.close();
        check(api.released_programs == 1);
        check(api.released_buffers == 2);
        check(api.released_events == 3);
    }

    std::cerr << "CASE quota" << std::endl;
    // Quota rejection is before fence claim or physical allocation.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length(), 1);
        auto prepared = provider.prepare(f.kernel, target(f));
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.queue, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(api.allocation_count == 0);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }

    std::cerr << "CASE queue" << std::endl;
    // Queue metadata cannot retarget the authenticated provider instance.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto foreign = f.queue;
        foreign.queue_id = 10;
        expect_throw<std::invalid_argument>([&] {
            (void)provider.begin(prepared, f.input, f.output, foreign, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        prepared.close();
    }

    std::cerr << "CASE pre-enqueue" << std::endl;
    // Allocation and first-enqueue failures roll back quota and RESERVED fences.
    {
        Fixture f;
        FakeApi api;
        api.fail_allocate_at = 2;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.queue, f.host);
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
        auto prepared = provider.prepare(f.kernel, target(f));
        expect_throw<std::runtime_error>([&] {
            (void)provider.begin(prepared, f.input, f.output, f.queue, f.host);
        });
        check(f.input_fence.status() == Status::Idle);
        check(f.output_fence.status() == Status::Idle);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        prepared.close();
    }

    std::cerr << "CASE post-submit" << std::endl;
    // A post-submit enqueue failure cannot use pre-enqueue abort.
    {
        Fixture f;
        FakeApi api;
        api.fail_kernel = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);

        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        check(f.input_fence.status() == Status::Poisoned);
        check(f.output_fence.status() == Status::Poisoned);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});

        f.input_fence.retire_after_backend_release({"tenant-a", "gpu0", 101});
        f.output_fence.retire_after_backend_release({"tenant-a", "gpu0", 102});
        prepared.close();
    }

    std::cerr << "CASE cancel" << std::endl;
    // Timeout/cancel holds resources until the exact device event is terminal.
    {
        Fixture f;
        FakeApi api;
        api.statuses.push_back(EventStatus::Pending);
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);

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

    std::cerr << "CASE device-loss" << std::endl;
    // Device loss is terminal failure evidence, never success.
    {
        Fixture f;
        FakeApi api;
        api.statuses.push_back(EventStatus::DeviceLost);
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);

        check(provider.poll(*operation) == Provider::OperationState::DeviceLost);
        check(f.input_fence.status() == Status::Poisoned);
        check(f.output_fence.status() == Status::Poisoned);
        expect_throw<std::logic_error>([&] { (void)operation->output_bytes(); });
        prepared.close();
    }

    std::cerr << "CASE release" << std::endl;
    // Failed physical release retains quota/program pin until explicit retry.
    {
        Fixture f;
        FakeApi api;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        std::cerr << "release:begin" << std::endl;
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);
        std::cerr << "release:poll1" << std::endl;
        (void)provider.poll(*operation);
        std::cerr << "release:poll2" << std::endl;
        (void)provider.poll(*operation);
        api.fail_release_buffer_once = true;

        std::cerr << "release:poll3" << std::endl;
        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        std::cerr << "release:after-poll3" << std::endl;
        check(operation->state() == Provider::OperationState::ReleaseFailed);
        check(provider.usage().resident_bytes > 0);
        expect_throw<std::logic_error>([&] { prepared.close(); });

        std::cerr << "release:retry" << std::endl;
        provider.retry_release(*operation);
        std::cerr << "release:after-retry" << std::endl;
        check(operation->state() == Provider::OperationState::Succeeded);
        check(provider.usage() == oreslang::opencl::Usage{0, 0, 0, 0});
        (void)operation->output_bytes();
        prepared.close();
    }

    std::cerr << "CASE event-release" << std::endl;
    // Terminal event release failure keeps the exact event resumable.
    {
        Fixture f;
        FakeApi api;
        api.fail_release_event_once = true;
        auto provider = make_provider(api, f.plan.byte_length());
        auto prepared = provider.prepare(f.kernel, target(f));
        auto operation = provider.begin(
                prepared, f.input, f.output, f.queue, f.host);

        expect_throw<std::runtime_error>([&] { (void)provider.poll(*operation); });
        check(provider.poll(*operation) == Provider::OperationState::KernelSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::DownloadSubmitted);
        check(provider.poll(*operation) == Provider::OperationState::Succeeded);
        prepared.close();
    }

    std::cout << "trusted OpenCL G3 provider state-machine tests passed\n";
    return 0;
}
