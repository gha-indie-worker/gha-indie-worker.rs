#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string_view>

namespace oreslang {

// Descriptive target ABI registry, not a promise of executable native cross-targets.
// Keep profiles identical to contracts/architecture/v1/targets.tsv.
class TargetAbi {
public:
    std::string_view triple() const noexcept { return triple_; }
    unsigned pointer_bits() const noexcept { return pointer_bits_; }
    std::string_view endian() const noexcept { return endian_; }
    std::string_view abi() const noexcept { return abi_; }
    std::string_view emission() const noexcept { return emission_; }

    static TargetAbi parse(std::string_view triple) {
        if (triple == "x86_64-unknown-linux-gnu")
            return {"x86_64-unknown-linux-gnu", 64, "little", "sysv", "unimplemented"};
        if (triple == "aarch64-unknown-linux-gnu")
            return {"aarch64-unknown-linux-gnu", 64, "little", "aapcs64", "unimplemented"};
        if (triple == "i686-unknown-linux-gnu")
            return {"i686-unknown-linux-gnu", 32, "little", "sysv", "unimplemented"};
        if (triple == "armv7-unknown-linux-gnueabihf")
            return {"armv7-unknown-linux-gnueabihf", 32, "little", "aapcs32-hardfp", "unimplemented"};
        if (triple == "thumbv7m-none-eabi")
            return {"thumbv7m-none-eabi", 32, "little", "aapcs32-soft", "experimental-cortexm-scalar-ir"};
        if (triple == "wasm32-unknown-unknown")
            return {"wasm32-unknown-unknown", 32, "little", "wasm-c", "experimental-wasm32-ir"};
        throw std::invalid_argument("unsupported target triple");
    }

    bool experimental_cortexm_ir() const noexcept {
        return emission_ == "experimental-cortexm-scalar-ir";
    }

    bool experimental_wasm32_ir() const noexcept {
        return emission_ == "experimental-wasm32-ir";
    }

    std::uint64_t max_contiguous_bytes() const noexcept {
        return pointer_bits_ == 32
            ? static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max())
            : static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());
    }

    std::uint64_t checked_contiguous_bytes(std::uint64_t bytes) const {
        if (bytes > max_contiguous_bytes())
            throw std::overflow_error("target contiguous allocation exceeds pointer distance");
        return bytes;
    }

    // Actual host allocation must be independently checked even when lowering
    // for a different target. Avoid size_t/ptrdiff_t narrowing and UB.
    static std::size_t checked_host_bytes(std::uint64_t bytes) {
        if (bytes > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())
                || bytes > static_cast<std::uint64_t>(std::numeric_limits<std::ptrdiff_t>::max()))
            throw std::overflow_error("host contiguous allocation exceeds addressable range");
        return static_cast<std::size_t>(bytes);
    }

private:
    TargetAbi(std::string_view triple, unsigned pointer_bits, std::string_view endian,
              std::string_view abi, std::string_view emission) noexcept
        : triple_(triple), pointer_bits_(pointer_bits), endian_(endian),
          abi_(abi), emission_(emission) {}

    const std::string_view triple_;
    const unsigned pointer_bits_;
    const std::string_view endian_;
    const std::string_view abi_;
    const std::string_view emission_;
};

} // namespace oreslang
