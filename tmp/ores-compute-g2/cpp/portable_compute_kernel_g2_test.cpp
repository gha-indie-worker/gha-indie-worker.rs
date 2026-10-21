#include "oreslang/runtime/DopKernelIR.hpp"

#include <cstdint>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sstream>

namespace {

std::uint32_t raw_bits(float value) {
    std::uint32_t bits = 0;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

} // namespace

int main() {
    using namespace oreslang::dop;

    for (const auto layout : {Layout::AoS, Layout::SoA, Layout::AoSoA}) {
        const auto lanes = layout == Layout::AoSoA ? 4u : 1u;
        Plan plan(layout, 5, 2, 4, 16, lanes);
        std::cout
            << "layout=" << static_cast<int>(layout)
            << " bytes=" << plan.byte_length()
            << " offsets=" << plan.byte_offset(0, 0)
            << "," << plan.byte_offset(1, 1)
            << "," << plan.byte_offset(4, 1)
            << "\n";

        HostBuffer input(plan), output(plan);
        for (std::uint64_t row = 0; row < plan.rows(); ++row) {
            for (std::uint32_t field = 0; field < plan.fields(); ++field) {
                input.write(row, field,
                    static_cast<float>(row * 10 + field) + 0.25f);
            }
        }

        for (const auto op : {Op::Scale, Op::Bias, Op::ScaleBias}) {
            Kernel kernel(plan, Element::F32, op, 1.5f, -2.0f);
            execute_cpu(kernel, input, output);

            std::ostringstream bits;
            bits << std::hex << std::setfill('0');
            bool first = true;
            for (std::uint64_t row = 0; row < plan.rows(); ++row) {
                for (std::uint32_t field = 0; field < plan.fields(); ++field) {
                    if (!first) bits << ",";
                    first = false;
                    bits << std::setw(8) << raw_bits(output.read(row, field));
                }
            }

            std::cout
                << "layout=" << static_cast<int>(layout)
                << " op=" << static_cast<int>(op)
                << " bits=" << bits.str()
                << "\n";
        }
    }

    return 0;
}
