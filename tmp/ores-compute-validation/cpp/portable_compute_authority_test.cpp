#include "oreslang/runtime/PortableComputeAuthority.hpp"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <type_traits>

namespace {

template <typename Fn>
void expect_throw(Fn&& fn) {
    bool threw = false;
    try {
        fn();
    } catch (const std::exception&) {
        threw = true;
    }
    if (!threw) std::abort();
}

void check(bool value) {
    if (!value) std::abort();
}

} // namespace

int main() {
    using namespace oreslang::compute;

    static_assert(!std::is_default_constructible_v<Device>);
    static_assert(!std::is_default_constructible_v<Fence>);
    static_assert(!std::is_copy_constructible_v<Fence>);
    static_assert(!std::is_copy_constructible_v<Buffer<unsigned char>>);
    static_assert(!std::is_move_constructible_v<Buffer<unsigned char>>);

    auto gpu = Authority::issue_device("vulkan", "gpu0", 7, "tenant-a");
    auto same_text_foreign = Authority::issue_device("vulkan", "gpu0", 7, "tenant-a");
    auto buffer = Authority::bind_buffer<unsigned char>(
        gpu, 11, 4096, 16, "u8", "actor:42");

    expect_throw([&] {
        (void)Authority::begin_upload(
            buffer, same_text_foreign, "actor:42", 0, 4);
    });
    expect_throw([&] {
        (void)Authority::begin_upload(buffer, gpu, "actor:99", 0, 4);
    });
    expect_throw([&] {
        (void)Authority::begin_upload(buffer, gpu, "actor:42", 4095, 2);
    });
    expect_throw([&] {
        (void)Authority::begin_upload(buffer, gpu, "actor:42", 0, 0);
    });
    expect_throw([&] {
        (void)Authority::begin_upload(buffer, gpu, "actor:42", -1, 4);
    });
    expect_throw([&] {
        (void)Authority::begin_upload(buffer, gpu, "actor:42", 0, -1);
    });

    auto first = Authority::begin_upload(buffer, gpu, "actor:42", 0, 64);
    check(first.direction() == TransferDirection::Upload);
    check(buffer.status() == oreslang::gpu::Status::InFlight);
    expect_throw([&] {
        (void)Authority::begin_download(buffer, gpu, "actor:42", 0, 4);
    });
    expect_throw([&] { Authority::complete(buffer, first, true); });
    Authority::mark_submitted(buffer, first);
    expect_throw([&] { Authority::mark_submitted(buffer, first); });
    expect_throw([&] { Authority::abort_before_enqueue(buffer, first); });
    Authority::complete(buffer, first, true);
    check(buffer.status() == oreslang::gpu::Status::Idle);

    auto second = Authority::begin_download(buffer, gpu, "actor:42", 64, 64);
    Authority::mark_submitted(buffer, second);
    expect_throw([&] { Authority::complete(buffer, first, true); });
    Authority::complete(buffer, second, true);

    auto cancelled = Authority::begin_download(buffer, gpu, "actor:42", 0, 16);
    expect_throw([&] { Authority::cancel(buffer, cancelled); });
    Authority::mark_submitted(buffer, cancelled);
    Authority::cancel(buffer, cancelled);
    check(buffer.status() == oreslang::gpu::Status::Poisoned);
    expect_throw([&] { Authority::retire(buffer, gpu, "actor:42"); });
    Authority::acknowledge_cancelled(buffer, cancelled);
    Authority::retire(buffer, gpu, "actor:42");
    check(buffer.status() == oreslang::gpu::Status::Closed);
    expect_throw([&] { Authority::retire(buffer, gpu, "actor:42"); });


    {
        auto reserved = Authority::bind_buffer<unsigned char>(
            gpu, 12, 128, 8, "u8", "actor:abort");
        auto fence = Authority::begin_upload(
            reserved, gpu, "actor:abort", 8, 16);
        expect_throw([&] { Authority::complete(reserved, fence, true); });
        Authority::abort_before_enqueue(reserved, fence);
        check(reserved.status() == oreslang::gpu::Status::Idle);
        expect_throw([&] { Authority::abort_before_enqueue(reserved, fence); });

        auto next = Authority::begin_upload(
            reserved, gpu, "actor:abort", 16, 16);
        check(next.sequence() != fence.sequence());
        Authority::mark_submitted(reserved, next);
        expect_throw([&] { Authority::abort_before_enqueue(reserved, next); });
        Authority::complete(reserved, next, true);
        Authority::retire(reserved, gpu, "actor:abort");
        check(reserved.status() == oreslang::gpu::Status::Closed);
    }

    expect_throw([] {
        (void)Authority::issue_device("vulkan", "gpu0", 0, "tenant-a");
    });
    expect_throw([&] {
        (void)Authority::bind_buffer<unsigned char>(
            gpu, 0, 64, 8, "u8", "actor:1");
    });
    expect_throw([&] {
        (void)Authority::bind_buffer<unsigned char>(
            gpu, 1, 64, 3, "u8", "actor:1");
    });
    expect_throw([&] {
        (void)Authority::bind_buffer<unsigned char>(
            gpu, 1,
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) + 1ULL,
            8, "u8", "actor:1");
    });

    std::cout << "portable compute authority tests passed\n";
    return 0;
}
