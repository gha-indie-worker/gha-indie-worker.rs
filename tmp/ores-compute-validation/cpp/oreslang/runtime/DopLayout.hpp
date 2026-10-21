#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>

namespace oreslang::dop {

// Checked plan for homogeneous POD fields; no allocation, device dispatch,
// pointer export, or implicit CPU fallback. Mirrors Java DataOrientedLayout.
enum class Layout { AoS, SoA, AoSoA };

inline bool power_of_two(std::uint64_t value) {
    return value != 0 && (value & (value - 1)) == 0;
}

inline std::uint64_t checked_add(std::uint64_t a, std::uint64_t b) {
    if (a > std::numeric_limits<std::uint64_t>::max() - b)
        throw std::overflow_error("DOP byte length overflow");
    return a + b;
}

inline std::uint64_t checked_mul(std::uint64_t a, std::uint64_t b) {
    if (b != 0 && a > std::numeric_limits<std::uint64_t>::max() / b)
        throw std::overflow_error("DOP byte length overflow");
    return a * b;
}

inline std::uint64_t align_up(std::uint64_t value, std::uint64_t alignment) {
    if (value == 0) return 0;
    return checked_mul(checked_add((value - 1) / alignment, 1), alignment);
}

class Plan {
public:
    Plan(Layout layout, std::uint64_t rows, std::uint32_t fields,
         std::uint32_t scalar_bytes, std::uint32_t alignment, std::uint32_t lanes)
        : layout_(layout), rows_(rows), fields_(fields),
          scalar_bytes_(scalar_bytes), alignment_(alignment), lanes_(lanes) {
        if (fields == 0 || !power_of_two(scalar_bytes)
                || !power_of_two(alignment) || scalar_bytes > alignment
                || alignment > 4096) {
            throw std::invalid_argument("invalid homogeneous POD scalar width/alignment");
        }
        if (!power_of_two(lanes) || lanes > 1024
                || (layout != Layout::AoSoA && lanes != 1)) {
            throw std::invalid_argument("invalid AoSoA lane count");
        }
        // The native ABI is currently limited to a signed 64-bit byte range,
        // like the Java implementation. Validate at descriptor construction.
        if (byte_length() > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()))
            throw std::overflow_error("DOP byte length exceeds signed 64-bit ABI");
    }

    Layout layout() const noexcept { return layout_; }
    std::uint64_t rows() const noexcept { return rows_; }
    std::uint32_t fields() const noexcept { return fields_; }
    std::uint32_t scalar_bytes() const noexcept { return scalar_bytes_; }
    std::uint32_t alignment() const noexcept { return alignment_; }
    std::uint32_t lanes() const noexcept { return lanes_; }

    std::uint64_t byte_length() const {
        switch (layout_) {
        case Layout::AoS:
            return checked_mul(rows_, align_up(checked_mul(fields_, scalar_bytes_), alignment_));
        case Layout::SoA:
            return checked_mul(fields_, align_up(checked_mul(rows_, scalar_bytes_), alignment_));
        case Layout::AoSoA: {
            const auto tiles = checked_add(rows_ / lanes_, rows_ % lanes_ == 0 ? 0 : 1);
            const auto tile_bytes = align_up(
                    checked_mul(checked_mul(fields_, lanes_), scalar_bytes_), alignment_);
            return checked_mul(tiles, tile_bytes);
        }
        }
        throw std::invalid_argument("invalid data layout");
    }

    std::uint64_t byte_offset(std::uint64_t row, std::uint32_t field) const {
        if (row >= rows_ || field >= fields_)
            throw std::out_of_range("DOP field/row out of bounds");
        switch (layout_) {
        case Layout::AoS:
            return checked_add(checked_mul(row,
                    align_up(checked_mul(fields_, scalar_bytes_), alignment_)),
                    checked_mul(field, scalar_bytes_));
        case Layout::SoA:
            return checked_add(checked_mul(field,
                    align_up(checked_mul(rows_, scalar_bytes_), alignment_)),
                    checked_mul(row, scalar_bytes_));
        case Layout::AoSoA: {
            const auto tile_bytes = align_up(
                    checked_mul(checked_mul(fields_, lanes_), scalar_bytes_), alignment_);
            return checked_add(
                    checked_add(checked_mul(row / lanes_, tile_bytes),
                            checked_mul(checked_mul(field, lanes_), scalar_bytes_)),
                    checked_mul(row % lanes_, scalar_bytes_));
        }
        }
        throw std::invalid_argument("invalid data layout");
    }
private:
    Layout layout_;
    std::uint64_t rows_;
    std::uint32_t fields_;
    std::uint32_t scalar_bytes_;
    std::uint32_t alignment_;
    std::uint32_t lanes_;
};

} // namespace oreslang::dop
