#pragma once

#include "oreslang/runtime/GpuBufferFence.hpp"

#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

namespace oreslang::compute {

enum class TransferDirection { Upload, Download };

class Authority;

class Device final {
    struct State {
        std::string backend_id;
        std::string device_id;
        std::uint64_t provider_generation;
        std::string tenant_domain;

        State(std::string backend, std::string device,
              std::uint64_t generation, std::string tenant)
            : backend_id(std::move(backend)),
              device_id(std::move(device)),
              provider_generation(generation),
              tenant_domain(std::move(tenant)) {}
    };

public:
    const std::string& backend_id() const noexcept { return state_->backend_id; }
    const std::string& device_id() const noexcept { return state_->device_id; }
    std::uint64_t provider_generation() const noexcept {
        return state_->provider_generation;
    }
    const std::string& tenant_domain() const noexcept {
        return state_->tenant_domain;
    }

private:
    explicit Device(std::shared_ptr<const State> state) noexcept
        : state_(std::move(state)) {}

    bool same_authority(const Device& other) const noexcept {
        return state_ == other.state_;
    }

    std::shared_ptr<const State> state_;
    friend class Authority;
};

class Fence final {
public:
    Fence(const Fence&) = delete;
    Fence& operator=(const Fence&) = delete;
    Fence(Fence&&) noexcept = default;
    Fence& operator=(Fence&&) = delete;

    std::uint64_t allocation_generation() const noexcept {
        return ticket_.generation();
    }
    std::uint64_t sequence() const noexcept { return ticket_.sequence(); }
    TransferDirection direction() const noexcept { return direction_; }
    std::int64_t offset() const noexcept { return offset_; }
    std::int64_t length() const noexcept { return length_; }

private:
    Fence(Device device, gpu::Ticket ticket, TransferDirection direction,
          std::int64_t offset, std::int64_t length) noexcept
        : device_(std::move(device)),
          ticket_(std::move(ticket)),
          direction_(direction),
          offset_(offset),
          length_(length) {}

    Device device_;
    gpu::Ticket ticket_;
    TransferDirection direction_;
    std::int64_t offset_;
    std::int64_t length_;

    friend class Authority;
};

template <typename T>
class Buffer final {
public:
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
    Buffer(Buffer&&) = delete;
    Buffer& operator=(Buffer&&) = delete;

    const Device& device() const noexcept { return device_; }
    std::uint64_t allocation_generation() const noexcept {
        return allocation_generation_;
    }
    std::uint64_t byte_length() const noexcept { return byte_length_; }
    std::uint64_t alignment() const noexcept { return alignment_; }
    const std::string& type_tag() const noexcept { return type_tag_; }
    const std::string& owner_domain() const noexcept { return owner_domain_; }
    gpu::Status status() const { return fence_.status(); }

private:
    Buffer(Device device, std::uint64_t allocation_generation,
           std::uint64_t byte_length, std::uint64_t alignment,
           std::string type_tag, std::string owner_domain)
        : device_(std::move(device)),
          allocation_generation_(allocation_generation),
          byte_length_(byte_length),
          alignment_(alignment),
          type_tag_(std::move(type_tag)),
          owner_domain_(std::move(owner_domain)),
          identity_{
              owner_domain_,
              device_.backend_id() + std::string("\0", 1)
                  + device_.device_id() + std::string("\0", 1)
                  + std::to_string(device_.provider_generation()),
              allocation_generation_},
          fence_(identity_, byte_length_) {
        if (allocation_generation_ == 0 || byte_length_ == 0
                || byte_length_ > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())
                || alignment_ == 0 || (alignment_ & (alignment_ - 1)) != 0
                || alignment_ > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())
                || type_tag_.empty() || owner_domain_.empty()) {
            throw std::invalid_argument("invalid portable compute buffer authority");
        }
    }

    Device device_;
    std::uint64_t allocation_generation_;
    std::uint64_t byte_length_;
    std::uint64_t alignment_;
    std::string type_tag_;
    std::string owner_domain_;
    gpu::Identity identity_;
    gpu::BufferFence fence_;

    friend class Authority;
};

class Authority final {
public:
    Authority() = delete;

    static Device issue_device(
            std::string backend_id,
            std::string device_id,
            std::uint64_t provider_generation,
            std::string tenant_domain) {
        if (backend_id.empty() || device_id.empty() || tenant_domain.empty()
                || provider_generation == 0) {
            throw std::invalid_argument("invalid portable compute device authority");
        }
        return Device(std::make_shared<const Device::State>(
                std::move(backend_id), std::move(device_id),
                provider_generation, std::move(tenant_domain)));
    }

    template <typename T>
    static Buffer<T> bind_buffer(
            Device device,
            std::uint64_t allocation_generation,
            std::uint64_t byte_length,
            std::uint64_t alignment,
            std::string type_tag,
            std::string owner_domain) {
        return Buffer<T>(
                std::move(device), allocation_generation, byte_length, alignment,
                std::move(type_tag), std::move(owner_domain));
    }

    template <typename T>
    static Fence begin_upload(
            Buffer<T>& buffer,
            const Device& expected_device,
            const std::string& expected_owner_domain,
            std::int64_t offset,
            std::int64_t length) {
        return begin_transfer(
                buffer, expected_device, expected_owner_domain,
                offset, length, TransferDirection::Upload);
    }

    template <typename T>
    static Fence begin_download(
            Buffer<T>& buffer,
            const Device& expected_device,
            const std::string& expected_owner_domain,
            std::int64_t offset,
            std::int64_t length) {
        return begin_transfer(
                buffer, expected_device, expected_owner_domain,
                offset, length, TransferDirection::Download);
    }

    template <typename T>
    static void mark_submitted(Buffer<T>& buffer, const Fence& fence) {
        validate_fence_device(buffer, fence);
        buffer.fence_.mark_submitted(fence.ticket_);
    }

    template <typename T>
    static void abort_before_enqueue(Buffer<T>& buffer, const Fence& fence) {
        validate_fence_device(buffer, fence);
        buffer.fence_.abort_unsubmitted(fence.ticket_);
    }

    template <typename T>
    static void complete(Buffer<T>& buffer, const Fence& fence, bool succeeded) {
        validate_fence_device(buffer, fence);
        buffer.fence_.complete(fence.ticket_, succeeded);
    }

    template <typename T>
    static void cancel(Buffer<T>& buffer, const Fence& fence) {
        validate_fence_device(buffer, fence);
        buffer.fence_.cancel_pending(fence.ticket_);
    }

    template <typename T>
    static void acknowledge_cancelled(Buffer<T>& buffer, const Fence& fence) {
        validate_fence_device(buffer, fence);
        buffer.fence_.acknowledge_cancelled_device_event(
                fence.ticket_, buffer.identity_);
    }

    template <typename T>
    static void retire(
            Buffer<T>& buffer,
            const Device& expected_device,
            const std::string& expected_owner_domain) {
        validate_owner(buffer, expected_device, expected_owner_domain);
        buffer.fence_.retire_after_backend_release(buffer.identity_);
    }

private:
    template <typename T>
    static Fence begin_transfer(
            Buffer<T>& buffer,
            const Device& expected_device,
            const std::string& expected_owner_domain,
            std::int64_t offset,
            std::int64_t length,
            TransferDirection direction) {
        validate_owner(buffer, expected_device, expected_owner_domain);
        if (offset < 0 || length <= 0) {
            throw std::invalid_argument("portable compute transfer outside allocation");
        }
        const auto unsigned_offset = static_cast<std::uint64_t>(offset);
        const auto unsigned_length = static_cast<std::uint64_t>(length);
        if (unsigned_offset > buffer.byte_length_
                || unsigned_length > buffer.byte_length_ - unsigned_offset) {
            throw std::invalid_argument("portable compute transfer outside allocation");
        }
        auto ticket = buffer.fence_.begin_command(buffer.identity_);
        return Fence(buffer.device_, std::move(ticket), direction, offset, length);
    }

    template <typename T>
    static void validate_owner(
            const Buffer<T>& buffer,
            const Device& expected_device,
            const std::string& expected_owner_domain) {
        if (!buffer.device_.same_authority(expected_device)
                || buffer.owner_domain_ != expected_owner_domain) {
            throw std::invalid_argument("foreign portable compute authority");
        }
    }

    template <typename T>
    static void validate_fence_device(const Buffer<T>& buffer, const Fence& fence) {
        if (!buffer.device_.same_authority(fence.device_)) {
            throw std::logic_error("foreign portable compute fence");
        }
    }
};

} // namespace oreslang::compute
