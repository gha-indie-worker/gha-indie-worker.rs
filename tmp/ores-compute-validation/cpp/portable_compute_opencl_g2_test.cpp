#include "oreslang/runtime/DopKernelIR.hpp"

#include <iostream>

int main() {
    using namespace oreslang::dop;

    for (const auto layout : {Layout::AoS, Layout::SoA, Layout::AoSoA}) {
        const auto lanes = layout == Layout::AoSoA ? 4u : 1u;
        Plan plan(layout, 5, 2, 4, 16, lanes);
        for (const auto op : {Op::Scale, Op::Bias, Op::ScaleBias}) {
            Kernel kernel(plan, Element::F32, op, 1.5f, -2.0f);
            const auto artifact = lower_opencl(kernel, {64, 4096, 100, true});
            std::cout
                << "layout=" << static_cast<int>(layout)
                << " op=" << static_cast<int>(op)
                << " bytes=" << artifact.required_bytes
                << " rows=" << artifact.global_rows
                << " fields=" << artifact.global_fields
                << "\n";
            std::cout << artifact.device_source;
            std::cout << "--\n";
        }
    }

    return 0;
}
