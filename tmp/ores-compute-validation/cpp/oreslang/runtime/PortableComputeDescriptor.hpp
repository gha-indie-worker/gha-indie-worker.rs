#pragma once

#include <cstdint>
#include <string>

namespace oreslang::compute {

// Portable, non-authoritative accelerator metadata.
//
// These descriptors intentionally carry no queue, allocation, fence, pointer,
// or actor-state authority. Source-visible std/compute values may map to these
// fields, while real Device/Buffer/Fence/KernelContext handles remain sealed
// compiler/runtime values.
struct DeviceCapabilities {
    std::string backend;
    std::string name;
    std::uint32_t address_bits;
    std::uint64_t max_buffer_bytes;
    std::uint32_t max_workgroup_size;
    bool float32;
    bool unified_memory;
};

struct BufferLayout {
    std::uint64_t element_bytes;
    std::uint64_t length;
    std::uint64_t alignment;
    std::uint64_t byte_length;
};

struct DispatchShape {
    std::uint64_t global_x;
    std::uint64_t global_y;
    std::uint64_t global_z;
    std::uint64_t local_x;
    std::uint64_t local_y;
    std::uint64_t local_z;
};

struct KernelRequirements {
    std::uint32_t address_bits;
    std::uint32_t max_workgroup_size;
    bool needs_float32;
    bool requires_unified_memory;
};

inline bool valid_device_capabilities(const DeviceCapabilities& caps) noexcept {
    if (caps.backend.empty() || caps.name.empty()) return false;
    if (caps.address_bits != 32 && caps.address_bits != 64) return false;
    if (caps.max_buffer_bytes == 0 || caps.max_workgroup_size == 0) return false;
    return true;
}

inline bool valid_buffer_layout(const BufferLayout& layout) noexcept {
    if (layout.element_bytes == 0 || layout.alignment == 0) return false;
    if (layout.length == 0) return layout.byte_length == 0;
    return layout.byte_length != 0;
}

inline bool valid_dispatch(const DispatchShape& shape) noexcept {
    if (shape.global_x == 0 || shape.global_y == 0 || shape.global_z == 0) return false;
    if (shape.local_x == 0 || shape.local_y == 0 || shape.local_z == 0) return false;
    if (shape.local_x > shape.global_x) return false;
    if (shape.local_y > shape.global_y) return false;
    if (shape.local_z > shape.global_z) return false;
    return true;
}

inline bool device_satisfies(
        const DeviceCapabilities& caps,
        const KernelRequirements& requirements) noexcept {
    if (!valid_device_capabilities(caps)) return false;
    if (requirements.address_bits != 32 && requirements.address_bits != 64) return false;
    if (caps.address_bits != requirements.address_bits) return false;
    if (requirements.max_workgroup_size == 0
            || requirements.max_workgroup_size > caps.max_workgroup_size) {
        return false;
    }
    if (requirements.needs_float32 && !caps.float32) return false;
    if (requirements.requires_unified_memory && !caps.unified_memory) return false;
    return true;
}

} // namespace oreslang::compute
