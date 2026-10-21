#pragma once

#include "oreslang/runtime/DopLayout.hpp"
#include "oreslang/runtime/TargetAbi.hpp"
#include <climits>
#include <cmath>
#include <limits>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <locale>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace oreslang::dop {

static_assert(CHAR_BIT == 8, "DOP byte offsets require 8-bit bytes");
static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559,
              "native F32 DOP requires IEEE-754 binary32 storage");

// The executable CPU lowering and emitted OpenCL source share one typed IR.
// Device execution is a separate, explicit capability; no CPU fallback.
enum class Element { F32 };
enum class Op { Scale, Bias, ScaleBias };

struct Kernel {
    // Constructor admission remains authoritative for the whole lifetime:
    // callers must not mutate enum tags or coefficients after validation.
    const Plan layout;
    const Element element;
    const Op operation;
    const float scale;
    const float bias;
    Kernel(Plan l, Element e, Op o, float a, float b)
        : layout(l), element(e), operation(o), scale(a), bias(b) {
        if (element != Element::F32 || layout.scalar_bytes() != sizeof(float)
                || (operation != Op::Scale && operation != Op::Bias
                    && operation != Op::ScaleBias)
                || !std::isfinite(scale) || !std::isfinite(bias))
            throw std::invalid_argument("only finite homogeneous f32 DOP kernels are admitted");
    }
};

struct GpuTarget {
    std::uint32_t address_bits;
    std::uint64_t maximum_buffer_bytes;
    std::uint64_t maximum_rows;
    bool float32;
};

struct OpenClArtifact {
    std::string kernel_name;
    std::string device_source;
    std::uint64_t required_bytes;
    std::uint64_t global_rows;
    std::uint32_t global_fields;
    std::uint32_t artifact_abi_version;
    std::uint32_t required_address_bits;
    bool required_float32;
    bool required_little_endian;
};

class HostBuffer {
    struct State {
        Plan layout;
        std::vector<unsigned char> bytes;
        mutable std::mutex lock;
        std::size_t loans = 0;
        bool closed = false;
        static std::size_t checked_host_size(const Plan& layout) {
            // HostBuffer read()/write() always copy sizeof(float) bytes. A
            // descriptor with smaller scalar slots used to corrupt adjacent
            // fields or overrun the backing allocation on the last slot.
            // Reject it before allocating storage or granting any loan.
            if (layout.scalar_bytes() != sizeof(float))
                throw std::invalid_argument("HostBuffer requires 4-byte F32 elements");
            // The current Java DOP byte-buffer ABI is little-endian. Do not
            // silently treat native big-endian float bytes as that ABI.
            const std::uint32_t probe = 1;
            if (*reinterpret_cast<const unsigned char*>(&probe) != 1)
                throw std::runtime_error("big-endian DOP HostBuffer not supported");
            return oreslang::TargetAbi::checked_host_bytes(layout.byte_length());
        }
        explicit State(Plan value)
            : layout(value), bytes(checked_host_size(value), 0) { }
    };
public:
    class ReadLoan {
    public:
        explicit ReadLoan(std::shared_ptr<State> state) : state_(std::move(state)) {}
        ReadLoan(const ReadLoan&) = delete;
        ReadLoan& operator=(const ReadLoan&) = delete;
        ReadLoan(ReadLoan&& other) noexcept : state_(std::move(other.state_)) {}
        ReadLoan& operator=(ReadLoan&&) = delete;
        ~ReadLoan() { release(); }
        float read(std::uint64_t row, std::uint32_t field) const {
            if (!state_) throw std::logic_error("stale read loan");
            std::lock_guard<std::mutex> guard(state_->lock);
            if (state_->closed) throw std::logic_error("closed read loan");
            const auto offset = state_->layout.byte_offset(row, field);
            float result = 0;
            std::memcpy(&result, state_->bytes.data() + static_cast<std::size_t>(offset), sizeof(result));
            return result;
        }
        void release() noexcept {
            if (!state_) return;
            auto held = std::move(state_);
            {
                std::lock_guard<std::mutex> guard(held->lock);
                --held->loans;
            } // The mutex must be unlocked before the last state reference dies.
        }
    private:
        std::shared_ptr<State> state_;
    };

    explicit HostBuffer(Plan layout) : state_(std::make_shared<State>(layout)) {}
    HostBuffer(const HostBuffer&) = delete;
    HostBuffer& operator=(const HostBuffer&) = delete;
    HostBuffer(HostBuffer&& other) {
        std::lock_guard<std::mutex> owner(other.owner_lock_);
        // Direct std::move must obey take(): a live loan cannot be
        // transferred behind its borrow owner's back.
        if (other.state_) {
            std::lock_guard<std::mutex> guard(other.state_->lock);
            if (other.state_->closed || other.state_->loans != 0)
                throw std::logic_error("cannot move closed or borrowed host buffer");
        }
        state_ = std::move(other.state_);
    }
    HostBuffer& operator=(HostBuffer&& other) {
        if (this == &other) return *this;
        std::scoped_lock owners(owner_lock_, other.owner_lock_);
        // Never silently drop a live allocation or an outstanding loan.
        if (state_) {
            std::lock_guard<std::mutex> state_guard(state_->lock);
            if (!state_->closed || state_->loans != 0)
                throw std::logic_error("cannot overwrite live owned host buffer");
        }
        // Reject transfer of a borrowed or closed source, not just an
        // unretired destination, while both owner locks are held.
        if (other.state_) {
            std::lock_guard<std::mutex> guard(other.state_->lock);
            if (other.state_->closed || other.state_->loans != 0)
                throw std::logic_error("cannot move closed or borrowed host buffer");
        }
        state_ = std::move(other.state_);
        return *this;
    }

    // Return a value: a borrowed reference to the owner-controlled State
    // could otherwise dangle when an owned buffer is moved and released.
    Plan layout() const {
        std::lock_guard<std::mutex> owner(owner_lock_);
        if (!state_) throw std::logic_error("buffer moved or closed");
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->closed) throw std::logic_error("buffer moved or closed");
        return state_->layout;
    }
    bool identical_storage(const HostBuffer& other) const noexcept {
        if (this == &other) {
            std::lock_guard<std::mutex> owner(owner_lock_);
            return static_cast<bool>(state_);
        }
        std::scoped_lock owners(owner_lock_, other.owner_lock_);
        return state_ && state_ == other.state_;
    }

    HostBuffer take() {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->loans != 0) throw std::logic_error("cannot move borrowed buffer");
        HostBuffer replacement(std::move(state_));
        return replacement;
    }

    ReadLoan borrow_read() const {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->closed) throw std::logic_error("closed buffer");
        if (state_->loans == std::numeric_limits<std::size_t>::max())
            throw std::overflow_error("read loan counter exhausted");
        ++state_->loans;
        return ReadLoan(state_);
    }

    float read(std::uint64_t row, std::uint32_t field) const {
        auto loan = borrow_read();
        return loan.read(row, field);
    }

    void write(std::uint64_t row, std::uint32_t field, float value) {
        std::lock_guard<std::mutex> owner(owner_lock_);
        active();
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->closed || state_->loans) throw std::logic_error("write to closed or borrowed buffer");
        const auto offset = state_->layout.byte_offset(row, field);
        std::memcpy(state_->bytes.data() + static_cast<std::size_t>(offset), &value, sizeof(value));
    }

    void close() {
        std::lock_guard<std::mutex> owner(owner_lock_);
        if (!state_) return; // relinquished ownership
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->loans != 0) throw std::logic_error("outstanding read loan");
        state_->closed = true;
    }

private:
    explicit HostBuffer(std::shared_ptr<State> state) : state_(std::move(state)) {}
    // Caller must hold owner_lock_ before inspecting the handle.
    void active() const {
        if (!state_) throw std::logic_error("buffer moved or closed");
        std::lock_guard<std::mutex> guard(state_->lock);
        if (state_->closed) throw std::logic_error("buffer moved or closed");
    }
    // Lock order is owner_lock_ -> State::lock, including owner moves.
    mutable std::mutex owner_lock_;
    std::shared_ptr<State> state_;
};

inline bool matching_layout(const Plan& left, const Plan& right) {
    return left.layout() == right.layout() && left.rows() == right.rows()
            && left.fields() == right.fields() && left.scalar_bytes() == right.scalar_bytes()
            && left.alignment() == right.alignment() && left.lanes() == right.lanes();
}

inline void execute_cpu(const Kernel& kernel, const HostBuffer& input, HostBuffer& output) {
    if (input.identical_storage(output)) throw std::invalid_argument("input/output alias forbidden");
    if (!matching_layout(kernel.layout, input.layout())
            || !matching_layout(kernel.layout, output.layout()))
        throw std::invalid_argument("kernel layout does not match owned buffers");
    auto loan = input.borrow_read();
    for (std::uint64_t row = 0; row < kernel.layout.rows(); ++row) {
        for (std::uint32_t field = 0; field < kernel.layout.fields(); ++field) {
            const auto v = loan.read(row, field);
            float result = 0.0f;
            if (kernel.operation == Op::Scale) {
                result = v * kernel.scale;
            } else if (kernel.operation == Op::Bias) {
                result = v + kernel.bias;
            } else {
                // The portable CPU reference semantics require a rounded f32
                // multiply followed by a rounded f32 add. Do not let a host
                // compiler contract this into FMA and diverge from Java/OpenCL.
                volatile float product = v * kernel.scale;
                result = product + kernel.bias;
            }
            output.write(row, field, result);
        }
    }
}

inline std::string cl_float(float value) {
    std::ostringstream result;
    // Kernel source is a language artifact, not locale-sensitive UI text.
    // A process-global decimal-comma locale would produce invalid OpenCL C.
    result.imbue(std::locale::classic());
    // max_digits10 significant digits preserve every finite f32 constant,
    // including tiny subnormals that fixed decimal precision would round to zero.
    result << std::scientific << std::setprecision(std::numeric_limits<float>::max_digits10 - 1)
           << value << "f";
    return result.str();
}

inline OpenClArtifact lower_opencl(const Kernel& kernel, GpuTarget target) {
    const auto& p = kernel.layout;
    // Empty host layouts are valid, but zero-work NDRange dispatch is not
    // a supported GPU capability; fail closed instead of emitting it.
    if (target.address_bits != 64 || !target.float32
            || target.maximum_buffer_bytes == 0 || target.maximum_rows == 0
            || p.rows() == 0 || p.byte_length() == 0
            || p.byte_length() > target.maximum_buffer_bytes
            || p.rows() > target.maximum_rows)
        throw std::invalid_argument("GPU target cannot admit kernel effects or buffer");
    std::string offset;
    switch (p.layout()) {
    case Layout::AoS:
        offset = "(row * " + std::to_string(align_up(checked_mul(p.fields(), 4), p.alignment()))
            + "UL + field * 4UL)";
        break;
    case Layout::SoA:
        offset = "(field * " + std::to_string(align_up(checked_mul(p.rows(), 4), p.alignment()))
            + "UL + row * 4UL)";
        break;
    case Layout::AoSoA:
        offset = "((row / " + std::to_string(p.lanes()) + "UL) * "
            + std::to_string(align_up(checked_mul(checked_mul(p.fields(), p.lanes()), 4), p.alignment()))
            + "UL + field * " + std::to_string(p.lanes() * 4) + "UL + (row % "
            + std::to_string(p.lanes()) + "UL) * 4UL)";
        break;
    }
    const auto rhs = kernel.operation == Op::Scale
        ? "(v * " + cl_float(kernel.scale) + ")"
        : kernel.operation == Op::Bias
        ? "(v + " + cl_float(kernel.bias) + ")"
        : "((v * " + cl_float(kernel.scale) + ") + " + cl_float(kernel.bias) + ")";
    const std::string name = "oreslang_dop_f32";
    std::string source = "#pragma OPENCL FP_CONTRACT OFF\n"
        "__kernel void " + name + "(__global const float* src, __global float* dst) {\n"
        "  const ulong row = (ulong)get_global_id(0);\n"
        "  const ulong field = (ulong)get_global_id(1);\n"
        "  if (row >= " + std::to_string(p.rows()) + "UL || field >= "
        + std::to_string(p.fields()) + "UL) return;\n"
        "  const ulong byteOffset = " + offset + ";\n"
        "  const ulong slot = byteOffset / 4UL;\n"
        "  const float v = src[slot];\n"
        "  dst[slot] = " + rhs + ";\n}\n";
    return OpenClArtifact{
        name,
        source,
        p.byte_length(),
        p.rows(),
        p.fields(),
        1,
        64,
        true,
        true};
}

} // namespace oreslang::dop
