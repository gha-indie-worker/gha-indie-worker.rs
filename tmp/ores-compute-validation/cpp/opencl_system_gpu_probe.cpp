#include "oreslang/runtime/OpenClSystemApi.hpp"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <thread>
#include <vector>

namespace {

void store_f32(
        std::vector<unsigned char>& bytes,
        std::uint64_t offset,
        float value) {
    std::memcpy(
            bytes.data() + static_cast<std::size_t>(offset),
            &value,
            sizeof(value));
}

float load_f32(
        const std::vector<unsigned char>& bytes,
        std::uint64_t offset) {
    float value = 0.0f;
    std::memcpy(
            &value,
            bytes.data() + static_cast<std::size_t>(offset),
            sizeof(value));
    return value;
}

bool terminal(oreslang::opencl::Provider::OperationState state) {
    using State = oreslang::opencl::Provider::OperationState;
    return state == State::Succeeded
        || state == State::Failed
        || state == State::Cancelled
        || state == State::DeviceLost
        || state == State::ReleaseFailed;
}

} // namespace

int main() {
    using namespace oreslang;

    auto api = opencl::SystemApi::try_create_gpu();
    if (!api) {
        std::cout << "opencl-real-gpu-probe-skipped:no-compatible-gpu\n";
        return 0;
    }

    const dop::Plan plan(dop::Layout::AoS, 4, 2, 4, 16, 1);
    const dop::Kernel kernel(
            plan,
            dop::Element::F32,
            dop::Op::ScaleBias,
            1.5f,
            -2.0f);
    const dop::GpuTarget target{
            64,
            api->maximum_allocation_bytes(),
            1024,
            true};

    const std::string tenant = "g3-real-probe";
    const std::string device = api->device_name();

    gpu::BufferFence input_fence(
            {tenant, device, 1},
            plan.byte_length());
    gpu::BufferFence output_fence(
            {tenant, device, 2},
            plan.byte_length());
    const dop::DeviceBuffer input(plan, input_fence);
    const dop::DeviceBuffer output(plan, output_fence);
    opencl::Provider provider(
            *api,
            {tenant, device, 1, 1, 4},
            {
                plan.byte_length() * 4,
                plan.byte_length() * 4,
                2,
                1
            },
            target);

    auto prepared = provider.prepare(kernel);

    std::vector<unsigned char> host(
            static_cast<std::size_t>(plan.byte_length()),
            0);
    for (std::uint64_t row = 0; row < plan.rows(); ++row) {
        for (std::uint32_t field = 0; field < plan.fields(); ++field) {
            const float value =
                    static_cast<float>(row * 10 + field) + 0.25f;
            store_f32(host, plan.byte_offset(row, field), value);
        }
    }

    auto operation = provider.begin(
            prepared,
            input,
            output,
            host);

    opencl::Provider::OperationState state = operation->state();
    for (int attempt = 0; attempt < 10000 && !terminal(state); ++attempt) {
        state = provider.poll(*operation);
        if (!terminal(state))
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    if (!terminal(state)) {
        provider.request_timeout_cancellation(*operation);
        for (int attempt = 0; attempt < 10000 && !terminal(state); ++attempt) {
            state = provider.poll(*operation);
            if (!terminal(state))
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    }

    if (state != opencl::Provider::OperationState::Succeeded) {
        std::cerr
            << "opencl-real-gpu-probe-failed:terminal-state="
            << static_cast<int>(state)
            << "\n";
        return 1;
    }

    const auto result = operation->output_bytes();
    for (std::uint64_t row = 0; row < plan.rows(); ++row) {
        for (std::uint32_t field = 0; field < plan.fields(); ++field) {
            const float input_value =
                    static_cast<float>(row * 10 + field) + 0.25f;
            const float product = input_value * 1.5f;
            const float expected = product - 2.0f;
            const float actual =
                    load_f32(result, plan.byte_offset(row, field));
            std::uint32_t expected_bits = 0;
            std::uint32_t actual_bits = 0;
            std::memcpy(&expected_bits, &expected, sizeof(expected_bits));
            std::memcpy(&actual_bits, &actual, sizeof(actual_bits));
            if (expected_bits != actual_bits) {
                std::cerr
                    << "opencl-real-gpu-probe-failed:result-mismatch"
                    << " row=" << row
                    << " field=" << field
                    << "\n";
                return 1;
            }
        }
    }

    if (!(provider.usage() == opencl::Usage{0, 0, 0, 0})) {
        std::cerr << "opencl-real-gpu-probe-failed:quota-not-released\n";
        return 1;
    }

    prepared.close();
    std::cout
        << "opencl-real-gpu-probe-passed:device="
        << api->device_name()
        << "\n";
    return 0;
}
