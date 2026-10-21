#include "oreslang/runtime/PortableComputeDescriptor.hpp"

#include <cstdlib>
#include <iostream>
#include <limits>

namespace {

void check(bool value) {
    if (!value) std::abort();
}

} // namespace

int main() {
    using namespace oreslang::compute;

    const DeviceCapabilities gpu{
        "vulkan", "test-gpu", 64, 1LL << 30, 256, true, false};
    check(valid_device_capabilities(gpu));
    check(!valid_device_capabilities(DeviceCapabilities{
        "vulkan", "bad-width", 48, 1024, 64, true, false}));
    check(!valid_device_capabilities(DeviceCapabilities{
        "", "missing-backend", 64, 1024, 64, true, false}));
    check(!valid_device_capabilities(DeviceCapabilities{
        "vulkan", "negative-buffer", 64, -1, 64, true, false}));
    check(!valid_device_capabilities(DeviceCapabilities{
        "vulkan", "negative-workgroup", 64, 1024, -1, true, false}));

    check(valid_buffer_layout(BufferLayout{4, 1024, 16, 4096}));
    check(valid_buffer_layout(BufferLayout{4, 0, 16, 0}));
    check(!valid_buffer_layout(BufferLayout{0, 1024, 16, 4096}));
    check(!valid_buffer_layout(BufferLayout{4, 0, 16, 1}));
    check(!valid_buffer_layout(BufferLayout{4, 1, 0, 4}));
    check(!valid_buffer_layout(BufferLayout{-4, 1, 16, 4}));
    check(!valid_buffer_layout(BufferLayout{4, -1, 16, 4}));
    check(!valid_buffer_layout(BufferLayout{4, 1, -16, 4}));
    check(!valid_buffer_layout(BufferLayout{4, 1, 16, -4}));
    check(valid_buffer_layout(BufferLayout{
        1, 1, 1, std::numeric_limits<std::int64_t>::max()}));

    check(valid_dispatch(DispatchShape{1024, 1, 1, 256, 1, 1}));
    check(!valid_dispatch(DispatchShape{1024, 1, 1, 2048, 1, 1}));
    check(!valid_dispatch(DispatchShape{0, 1, 1, 1, 1, 1}));
    check(!valid_dispatch(DispatchShape{-1, 1, 1, 1, 1, 1}));
    check(!valid_dispatch(DispatchShape{1, 1, 1, -1, 1, 1}));

    check(device_satisfies(
        gpu, KernelRequirements{64, 256, true, false}));
    check(!device_satisfies(
        gpu, KernelRequirements{32, 256, true, false}));
    check(!device_satisfies(
        gpu, KernelRequirements{64, 512, true, false}));
    check(!device_satisfies(
        gpu, KernelRequirements{64, 256, true, true}));
    check(!device_satisfies(
        gpu, KernelRequirements{64, -1, true, false}));

    const DeviceCapabilities unified{
        "metal", "unified-gpu", 64, 1LL << 30, 1024, true, true};
    check(device_satisfies(
        unified, KernelRequirements{64, 512, true, true}));

    std::cout << "portable compute descriptor tests passed\n";
    return 0;
}
