#pragma once

#include "oreslang/runtime/OpenClProvider.hpp"

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace oreslang::opencl {

class SystemApi final : public Api {
    using cl_char = std::int8_t;
    using cl_int = std::int32_t;
    using cl_uint = std::uint32_t;
    using cl_ulong = std::uint64_t;
    using cl_bitfield = cl_ulong;
    using cl_device_type = cl_bitfield;
    using cl_mem_flags = cl_bitfield;
    using cl_command_queue_properties = cl_bitfield;
    using cl_device_fp_config = cl_bitfield;
    using cl_context_properties = std::intptr_t;
    using cl_bool = cl_uint;

    struct _cl_platform_id;
    struct _cl_device_id;
    struct _cl_context;
    struct _cl_command_queue;
    struct _cl_mem;
    struct _cl_program;
    struct _cl_kernel;
    struct _cl_event;

    using cl_platform_id = _cl_platform_id*;
    using cl_device_id = _cl_device_id*;
    using cl_context = _cl_context*;
    using cl_command_queue = _cl_command_queue*;
    using cl_mem = _cl_mem*;
    using cl_program = _cl_program*;
    using cl_kernel = _cl_kernel*;
    using cl_event = _cl_event*;

    static constexpr cl_int CL_SUCCESS = 0;
    static constexpr cl_int CL_DEVICE_NOT_AVAILABLE = -2;
    static constexpr cl_int CL_COMPLETE = 0;
    static constexpr cl_device_type CL_DEVICE_TYPE_GPU = 1ULL << 2;
    static constexpr cl_mem_flags CL_MEM_READ_WRITE = 1ULL << 0;
    static constexpr cl_bool CL_FALSE = 0;
    static constexpr cl_uint CL_DEVICE_ADDRESS_BITS = 0x100D;
    static constexpr cl_uint CL_DEVICE_MAX_MEM_ALLOC_SIZE = 0x1010;
    static constexpr cl_uint CL_DEVICE_SINGLE_FP_CONFIG = 0x101B;
    static constexpr cl_uint CL_DEVICE_ENDIAN_LITTLE = 0x1026;
    static constexpr cl_uint CL_DEVICE_NAME = 0x102B;
    static constexpr cl_uint CL_EVENT_COMMAND_EXECUTION_STATUS = 0x1283;

    using ContextNotify = void (*)(
            const char*, const void*, std::size_t, void*);
    using ProgramNotify = void (*)(cl_program, void*);

    struct Dispatch {
        cl_int (*get_platform_ids)(cl_uint, cl_platform_id*, cl_uint*) = nullptr;
        cl_int (*get_device_ids)(
                cl_platform_id, cl_device_type, cl_uint, cl_device_id*, cl_uint*) = nullptr;
        cl_int (*get_device_info)(
                cl_device_id, cl_uint, std::size_t, void*, std::size_t*) = nullptr;
        cl_context (*create_context)(
                const cl_context_properties*, cl_uint, const cl_device_id*,
                ContextNotify, void*, cl_int*) = nullptr;
        cl_int (*release_context)(cl_context) = nullptr;
        cl_command_queue (*create_command_queue)(
                cl_context, cl_device_id, cl_command_queue_properties, cl_int*) = nullptr;
        cl_int (*release_command_queue)(cl_command_queue) = nullptr;
        cl_int (*finish)(cl_command_queue) = nullptr;
        cl_int (*flush)(cl_command_queue) = nullptr;
        cl_mem (*create_buffer)(
                cl_context, cl_mem_flags, std::size_t, void*, cl_int*) = nullptr;
        cl_int (*release_mem_object)(cl_mem) = nullptr;
        cl_program (*create_program_with_source)(
                cl_context, cl_uint, const char**, const std::size_t*, cl_int*) = nullptr;
        cl_int (*build_program)(
                cl_program, cl_uint, const cl_device_id*, const char*,
                ProgramNotify, void*) = nullptr;
        cl_int (*release_program)(cl_program) = nullptr;
        cl_kernel (*create_kernel)(cl_program, const char*, cl_int*) = nullptr;
        cl_int (*release_kernel)(cl_kernel) = nullptr;
        cl_int (*set_kernel_arg)(cl_kernel, cl_uint, std::size_t, const void*) = nullptr;
        cl_int (*enqueue_write_buffer)(
                cl_command_queue, cl_mem, cl_bool, std::size_t, std::size_t,
                const void*, cl_uint, const cl_event*, cl_event*) = nullptr;
        cl_int (*enqueue_ndrange_kernel)(
                cl_command_queue, cl_kernel, cl_uint, const std::size_t*,
                const std::size_t*, const std::size_t*,
                cl_uint, const cl_event*, cl_event*) = nullptr;
        cl_int (*enqueue_read_buffer)(
                cl_command_queue, cl_mem, cl_bool, std::size_t, std::size_t,
                void*, cl_uint, const cl_event*, cl_event*) = nullptr;
        cl_int (*get_event_info)(
                cl_event, cl_uint, std::size_t, void*, std::size_t*) = nullptr;
        cl_int (*release_event)(cl_event) = nullptr;
    };

    struct Prepared {
        cl_program program = nullptr;
        cl_kernel kernel = nullptr;
    };

    struct Event {
        cl_event event = nullptr;
        bool device_lost = false;
        bool flush_failed = false;
    };

public:
    SystemApi(const SystemApi&) = delete;
    SystemApi& operator=(const SystemApi&) = delete;

    ~SystemApi() override {
        if (queue_ && dispatch_.finish)
            (void)dispatch_.finish(queue_);
        if (queue_ && dispatch_.release_command_queue)
            (void)dispatch_.release_command_queue(queue_);
        if (context_ && dispatch_.release_context)
            (void)dispatch_.release_context(context_);
        close_library(library_);
    }

    static std::unique_ptr<SystemApi> try_create_gpu() {
        try {
            return std::unique_ptr<SystemApi>(new SystemApi());
        } catch (...) {
            return nullptr;
        }
    }

    const std::string& device_name() const noexcept {
        return device_name_;
    }

    std::uint64_t maximum_allocation_bytes() const noexcept {
        return maximum_allocation_bytes_;
    }

    Handle prepare(const dop::OpenClArtifact& artifact) override {
        auto prepared = std::make_unique<Prepared>();

        try {
            const char* source = artifact.device_source.c_str();
            const std::size_t source_length = artifact.device_source.size();
            cl_int status = CL_SUCCESS;
            prepared->program = dispatch_.create_program_with_source(
                    context_, 1, &source, &source_length, &status);
            require_success(status, "clCreateProgramWithSource");
            if (!prepared->program)
                throw std::runtime_error("OpenCL returned null program");

            status = dispatch_.build_program(
                    prepared->program, 1, &device_, nullptr, nullptr, nullptr);
            require_success(status, "clBuildProgram");

            prepared->kernel = dispatch_.create_kernel(
                    prepared->program, artifact.kernel_name.c_str(), &status);
            require_success(status, "clCreateKernel");
            if (!prepared->kernel)
                throw std::runtime_error("OpenCL returned null kernel");

            return prepared.release();
        } catch (...) {
            if (prepared->kernel)
                (void)dispatch_.release_kernel(prepared->kernel);
            if (prepared->program)
                (void)dispatch_.release_program(prepared->program);
            throw;
        }
    }

    void release_program(Handle program) override {
        auto* prepared = require_handle<Prepared>(program, "program");
        if (prepared->kernel) {
            require_success(
                    dispatch_.release_kernel(prepared->kernel),
                    "clReleaseKernel");
            prepared->kernel = nullptr;
        }
        if (prepared->program) {
            require_success(
                    dispatch_.release_program(prepared->program),
                    "clReleaseProgram");
            prepared->program = nullptr;
        }
        delete prepared;
    }

    Handle allocate(std::uint64_t bytes) override {
        if (bytes == 0
                || bytes > maximum_allocation_bytes_
                || bytes > static_cast<std::uint64_t>(SIZE_MAX))
            throw std::invalid_argument("OpenCL allocation outside device limits");

        cl_int status = CL_SUCCESS;
        cl_mem memory = dispatch_.create_buffer(
                context_,
                CL_MEM_READ_WRITE,
                static_cast<std::size_t>(bytes),
                nullptr,
                &status);
        require_success(status, "clCreateBuffer");
        if (!memory)
            throw std::runtime_error("OpenCL returned null memory object");
        return memory;
    }

    void release_buffer(Handle buffer) override {
        auto memory = reinterpret_cast<cl_mem>(buffer);
        if (!memory)
            throw std::invalid_argument("null OpenCL memory object");
        require_success(
                dispatch_.release_mem_object(memory),
                "clReleaseMemObject");
    }

    Handle enqueue_upload(
            Handle destination,
            const unsigned char* bytes,
            std::size_t length) override {
        if (!destination || !bytes || length == 0)
            throw std::invalid_argument("invalid OpenCL upload");
        auto event = std::make_unique<Event>();
        cl_event raw = nullptr;
        const auto status = dispatch_.enqueue_write_buffer(
                queue_,
                reinterpret_cast<cl_mem>(destination),
                CL_FALSE,
                0,
                length,
                bytes,
                0,
                nullptr,
                &raw);
        require_success(status, "clEnqueueWriteBuffer");
        if (!raw)
            throw std::runtime_error("OpenCL upload returned null event");
        event->event = raw;
        apply_flush_result(*event);
        return event.release();
    }

    Handle enqueue_kernel(
            Handle program,
            Handle input,
            Handle output,
            std::uint64_t global_rows,
            std::uint32_t global_fields) override {
        auto* prepared = require_handle<Prepared>(program, "program");
        auto input_mem = reinterpret_cast<cl_mem>(input);
        auto output_mem = reinterpret_cast<cl_mem>(output);
        if (!input_mem || !output_mem || global_rows == 0 || global_fields == 0)
            throw std::invalid_argument("invalid OpenCL kernel launch");

        require_success(
                dispatch_.set_kernel_arg(
                        prepared->kernel, 0, sizeof(input_mem), &input_mem),
                "clSetKernelArg(input)");
        require_success(
                dispatch_.set_kernel_arg(
                        prepared->kernel, 1, sizeof(output_mem), &output_mem),
                "clSetKernelArg(output)");

        auto event = std::make_unique<Event>();
        cl_event raw = nullptr;
        const std::size_t global[2] = {
            checked_size(global_rows),
            static_cast<std::size_t>(global_fields)
        };
        const auto status = dispatch_.enqueue_ndrange_kernel(
                queue_,
                prepared->kernel,
                2,
                nullptr,
                global,
                nullptr,
                0,
                nullptr,
                &raw);
        require_success(status, "clEnqueueNDRangeKernel");
        if (!raw)
            throw std::runtime_error("OpenCL kernel returned null event");
        event->event = raw;
        apply_flush_result(*event);
        return event.release();
    }

    Handle enqueue_download(
            Handle source,
            unsigned char* bytes,
            std::size_t length) override {
        if (!source || !bytes || length == 0)
            throw std::invalid_argument("invalid OpenCL download");
        auto event = std::make_unique<Event>();
        cl_event raw = nullptr;
        const auto status = dispatch_.enqueue_read_buffer(
                queue_,
                reinterpret_cast<cl_mem>(source),
                CL_FALSE,
                0,
                length,
                bytes,
                0,
                nullptr,
                &raw);
        require_success(status, "clEnqueueReadBuffer");
        if (!raw)
            throw std::runtime_error("OpenCL download returned null event");
        event->event = raw;
        apply_flush_result(*event);
        return event.release();
    }

    EventStatus poll_event(Handle event_handle) override {
        auto* wrapper = require_handle<Event>(event_handle, "event");
        if (wrapper->device_lost)
            return EventStatus::DeviceLost;

        cl_int execution = 0;
        const auto status = dispatch_.get_event_info(
                wrapper->event,
                CL_EVENT_COMMAND_EXECUTION_STATUS,
                sizeof(execution),
                &execution,
                nullptr);
        if (status == CL_DEVICE_NOT_AVAILABLE)
            return EventStatus::DeviceLost;
        if (status != CL_SUCCESS)
            return EventStatus::Failed;
        if (execution == CL_COMPLETE)
            return EventStatus::Complete;
        if (execution < 0)
            return EventStatus::Failed;
        return EventStatus::Pending;
    }

    void release_event(Handle event_handle) override {
        auto* wrapper = require_handle<Event>(event_handle, "event");
        const auto status = dispatch_.release_event(wrapper->event);
        if (status != CL_SUCCESS)
            throw std::runtime_error(
                    "clReleaseEvent failed: " + std::to_string(status));
        wrapper->event = nullptr;
        delete wrapper;
    }

private:
#if defined(_WIN32)
    using Library = HMODULE;
#else
    using Library = void*;
#endif

    SystemApi() {
        try {
            initialize();
        } catch (...) {
            if (queue_ && dispatch_.release_command_queue)
                (void)dispatch_.release_command_queue(queue_);
            queue_ = nullptr;
            if (context_ && dispatch_.release_context)
                (void)dispatch_.release_context(context_);
            context_ = nullptr;
            close_library(library_);
            library_ = nullptr;
            throw;
        }
    }

    void initialize() {
        library_ = open_library();
        if (!library_)
            throw std::runtime_error("OpenCL runtime library unavailable");
        load_dispatch();

        cl_uint platform_count = 0;
        require_success(
                dispatch_.get_platform_ids(0, nullptr, &platform_count),
                "clGetPlatformIDs(count)");
        if (platform_count == 0)
            throw std::runtime_error("OpenCL has no platform");

        std::unique_ptr<cl_platform_id[]> platforms(
                new cl_platform_id[platform_count]);
        require_success(
                dispatch_.get_platform_ids(
                        platform_count, platforms.get(), nullptr),
                "clGetPlatformIDs");

        for (cl_uint p = 0; p < platform_count && !device_; ++p) {
            cl_uint count = 0;
            const auto status = dispatch_.get_device_ids(
                    platforms[p],
                    CL_DEVICE_TYPE_GPU,
                    0,
                    nullptr,
                    &count);
            if (status != CL_SUCCESS || count == 0)
                continue;
            std::unique_ptr<cl_device_id[]> devices(new cl_device_id[count]);
            if (dispatch_.get_device_ids(
                        platforms[p],
                        CL_DEVICE_TYPE_GPU,
                        count,
                        devices.get(),
                        nullptr) == CL_SUCCESS) {
                device_ = devices[0];
            }
        }
        if (!device_)
            throw std::runtime_error(
                    "OpenCL has no GPU device; CPU fallback is forbidden");

        cl_uint address_bits = 0;
        require_success(
                dispatch_.get_device_info(
                        device_,
                        CL_DEVICE_ADDRESS_BITS,
                        sizeof(address_bits),
                        &address_bits,
                        nullptr),
                "clGetDeviceInfo(address bits)");
        if (address_bits != 64)
            throw std::runtime_error("OpenCL GPU is not 64-bit address capable");

        cl_device_fp_config fp32 = 0;
        require_success(
                dispatch_.get_device_info(
                        device_,
                        CL_DEVICE_SINGLE_FP_CONFIG,
                        sizeof(fp32),
                        &fp32,
                        nullptr),
                "clGetDeviceInfo(fp32)");
        if (fp32 == 0)
            throw std::runtime_error("OpenCL GPU lacks admitted f32 support");

        cl_bool endian_little = 0;
        require_success(
                dispatch_.get_device_info(
                        device_,
                        CL_DEVICE_ENDIAN_LITTLE,
                        sizeof(endian_little),
                        &endian_little,
                        nullptr),
                "clGetDeviceInfo(endianness)");
        if (endian_little == 0)
            throw std::runtime_error(
                    "OpenCL GPU endianness is incompatible with portable f32 ABI");

        const std::uint32_t host_endian_probe = 1;
        if (*reinterpret_cast<const unsigned char*>(&host_endian_probe) != 1)
            throw std::runtime_error(
                    "host endianness is incompatible with portable f32 ABI");

        cl_ulong max_alloc = 0;
        require_success(
                dispatch_.get_device_info(
                        device_,
                        CL_DEVICE_MAX_MEM_ALLOC_SIZE,
                        sizeof(max_alloc),
                        &max_alloc,
                        nullptr),
                "clGetDeviceInfo(max allocation)");
        maximum_allocation_bytes_ = max_alloc;

        std::size_t name_bytes = 0;
        require_success(
                dispatch_.get_device_info(
                        device_, CL_DEVICE_NAME, 0, nullptr, &name_bytes),
                "clGetDeviceInfo(name length)");
        if (name_bytes == 0 || name_bytes > 4096)
            throw std::runtime_error("OpenCL GPU name length invalid");
        std::unique_ptr<char[]> name(new char[name_bytes]);
        require_success(
                dispatch_.get_device_info(
                        device_, CL_DEVICE_NAME, name_bytes, name.get(), nullptr),
                "clGetDeviceInfo(name)");
        device_name_.assign(name.get());
        if (device_name_.empty())
            device_name_ = "opencl-gpu";

        cl_int status = CL_SUCCESS;
        context_ = dispatch_.create_context(
                nullptr, 1, &device_, nullptr, nullptr, &status);
        require_success(status, "clCreateContext");
        if (!context_)
            throw std::runtime_error("OpenCL returned null context");

        queue_ = dispatch_.create_command_queue(
                context_, device_, 0, &status);
        require_success(status, "clCreateCommandQueue");
        if (!queue_)
            throw std::runtime_error("OpenCL returned null command queue");
    }

    void apply_flush_result(Event& event) {
        const auto status = dispatch_.flush(queue_);
        if (status == CL_SUCCESS)
            return;
        if (status == CL_DEVICE_NOT_AVAILABLE) {
            event.device_lost = true;
            return;
        }
        // The enqueue already returned a real cl_event. A flush error does
        // not prove that the queued command is quiescent, so retain the event
        // and let poll_event observe its actual terminal state. A caller may
        // request timeout cancellation while resources remain pinned.
        event.flush_failed = true;
    }

    static std::size_t checked_size(std::uint64_t value) {
        if (value == 0
                || value > static_cast<std::uint64_t>(SIZE_MAX))
            throw std::overflow_error("OpenCL NDRange size outside host ABI");
        return static_cast<std::size_t>(value);
    }

    template <typename T>
    static T* require_handle(Handle handle, const char* kind) {
        if (!handle)
            throw std::invalid_argument(std::string("null OpenCL ") + kind);
        return static_cast<T*>(handle);
    }

    static void require_success(cl_int status, const char* operation) {
        if (status != CL_SUCCESS)
            throw std::runtime_error(
                    std::string(operation) + " failed: "
                    + std::to_string(status));
    }

    void load_dispatch() {
#define ORES_LOAD_OPENCL(field, symbol) \
        dispatch_.field = reinterpret_cast<decltype(dispatch_.field)>( \
                load_symbol(library_, symbol)); \
        if (!dispatch_.field) \
            throw std::runtime_error(std::string("missing OpenCL symbol: ") + symbol)

        ORES_LOAD_OPENCL(get_platform_ids, "clGetPlatformIDs");
        ORES_LOAD_OPENCL(get_device_ids, "clGetDeviceIDs");
        ORES_LOAD_OPENCL(get_device_info, "clGetDeviceInfo");
        ORES_LOAD_OPENCL(create_context, "clCreateContext");
        ORES_LOAD_OPENCL(release_context, "clReleaseContext");
        ORES_LOAD_OPENCL(create_command_queue, "clCreateCommandQueue");
        ORES_LOAD_OPENCL(release_command_queue, "clReleaseCommandQueue");
        ORES_LOAD_OPENCL(finish, "clFinish");
        ORES_LOAD_OPENCL(flush, "clFlush");
        ORES_LOAD_OPENCL(create_buffer, "clCreateBuffer");
        ORES_LOAD_OPENCL(release_mem_object, "clReleaseMemObject");
        ORES_LOAD_OPENCL(create_program_with_source, "clCreateProgramWithSource");
        ORES_LOAD_OPENCL(build_program, "clBuildProgram");
        ORES_LOAD_OPENCL(release_program, "clReleaseProgram");
        ORES_LOAD_OPENCL(create_kernel, "clCreateKernel");
        ORES_LOAD_OPENCL(release_kernel, "clReleaseKernel");
        ORES_LOAD_OPENCL(set_kernel_arg, "clSetKernelArg");
        ORES_LOAD_OPENCL(enqueue_write_buffer, "clEnqueueWriteBuffer");
        ORES_LOAD_OPENCL(enqueue_ndrange_kernel, "clEnqueueNDRangeKernel");
        ORES_LOAD_OPENCL(enqueue_read_buffer, "clEnqueueReadBuffer");
        ORES_LOAD_OPENCL(get_event_info, "clGetEventInfo");
        ORES_LOAD_OPENCL(release_event, "clReleaseEvent");
#undef ORES_LOAD_OPENCL
    }

    static Library open_library() {
#if defined(_WIN32)
        return LoadLibraryA("OpenCL.dll");
#elif defined(__APPLE__)
        return dlopen(
                "/System/Library/Frameworks/OpenCL.framework/OpenCL",
                RTLD_NOW | RTLD_LOCAL);
#else
        Library library = dlopen("libOpenCL.so.1", RTLD_NOW | RTLD_LOCAL);
        if (!library)
            library = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        return library;
#endif
    }

    static void close_library(Library library) noexcept {
        if (!library) return;
#if defined(_WIN32)
        FreeLibrary(library);
#else
        dlclose(library);
#endif
    }

    static void* load_symbol(Library library, const char* name) noexcept {
#if defined(_WIN32)
        return reinterpret_cast<void*>(GetProcAddress(library, name));
#else
        return dlsym(library, name);
#endif
    }

    Library library_ = nullptr;
    Dispatch dispatch_;
    cl_device_id device_ = nullptr;
    cl_context context_ = nullptr;
    cl_command_queue queue_ = nullptr;
    std::string device_name_;
    std::uint64_t maximum_allocation_bytes_ = 0;
};

} // namespace oreslang::opencl
