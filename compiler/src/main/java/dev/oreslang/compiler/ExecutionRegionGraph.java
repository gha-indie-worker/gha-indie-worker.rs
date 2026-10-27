package dev.oreslang.compiler;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Experimental typed CPU/GPU execution-region + Task graph.
 * This is a compiler contract foundation, not a claim of a production GPU provider.
 * Pure synchronous CPU i64 plans can be interpreted by executeCpuI64().
 */
public final class ExecutionRegionGraph {
    private ExecutionRegionGraph() {}

    public enum TypeKind { VOID, I64, HOST_BUFFER_F32, GPU_BUFFER_F32, FUTURE }
    public record Type(TypeKind kind, Type inner) {
        public Type {
            Objects.requireNonNull(kind);
            if ((kind == TypeKind.FUTURE) != (inner != null))
                throw new IllegalArgumentException("Only Future has inner type");
            if (kind == TypeKind.FUTURE && inner.kind() == TypeKind.FUTURE)
                throw new IllegalArgumentException("nested Future requires explicit await/composition");
        }
        public static Type future(Type value) {
            Objects.requireNonNull(value);
            return new Type(TypeKind.FUTURE, value);
        }
    }

    public static final Type VOID = new Type(TypeKind.VOID, null);
    public static final Type I64 = new Type(TypeKind.I64, null);
    public static final Type HOST_F32 = new Type(TypeKind.HOST_BUFFER_F32, null);
    public static final Type GPU_F32 = new Type(TypeKind.GPU_BUFFER_F32, null);

    public enum LaunchMode { SYNC, TASK }

    public sealed interface Expr permits CpuRegion, GpuRegion, I64Literal, Add,
            Parameter, Upload, Download, DeviceKernel, Await, Sequence {}

    public record CpuRegion(Expr body, LaunchMode mode) implements Expr {
        public CpuRegion { Objects.requireNonNull(body); Objects.requireNonNull(mode); }
        public CpuRegion(Expr body, boolean legacyNb) {
            this(body, legacyNb ? LaunchMode.TASK : LaunchMode.SYNC);
        }
    }
    public record GpuRegion(Expr body, LaunchMode mode) implements Expr {
        public GpuRegion { Objects.requireNonNull(body); Objects.requireNonNull(mode); }
        public GpuRegion(Expr body, boolean legacyNb) {
            this(body, legacyNb ? LaunchMode.TASK : LaunchMode.SYNC);
        }
    }
    public record I64Literal(long value) implements Expr {}
    public record Add(Expr left, Expr right) implements Expr {
        public Add { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    public record Parameter(String name, Type type) implements Expr {
        public Parameter { validName(name); Objects.requireNonNull(type); }
    }
    public record Upload(Expr value) implements Expr {
        public Upload { Objects.requireNonNull(value); }
    }
    public record Download(Expr value) implements Expr {
        public Download { Objects.requireNonNull(value); }
    }
    public record DeviceKernel(String name, Expr value) implements Expr {
        public DeviceKernel { validName(name); Objects.requireNonNull(value); }
    }
    public record Await(Expr value) implements Expr {
        public Await { Objects.requireNonNull(value); }
    }
    public record Sequence(List<Expr> values) implements Expr {
        public Sequence { values = List.copyOf(values); }
    }

    public enum Effect { HOST, DEVICE, TRANSFER, SUBMIT, SUSPEND }
    public enum Domain { CPU, GPU, HOST, TRANSFER }
    public enum Opcode {
        CONST_I64, ADD_I64, PARAM, UPLOAD, DOWNLOAD, DEVICE_KERNEL,
        DEVICE_FENCE, DEVICE_ADMISSION, TASK_SUBMIT, TASK_COMPLETE, AWAIT
    }
    public record Step(int id, Opcode opcode, Domain domain, Type type,
                       List<Integer> dependencies, int left, int right, String detail, Long literal) {
        public Step { dependencies = List.copyOf(dependencies); }
    }
    public record Plan(List<Step> steps, Type resultType, int returnId,
                       List<Effect> effects, int deviceKernels, int taskSubmissions) {
        public Plan {
            steps = List.copyOf(steps);
            effects = List.copyOf(effects);
        }
    }
    private record Value(Type type, int id) {}

    private static void validName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("invalid region identifier");
    }
    private static IllegalArgumentException reject(String why) {
        return new IllegalArgumentException("execution region: " + why);
    }

    public static Plan lower(Expr root, Map<String, Type> parameters) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(parameters);
        if (parameters.size() > 64) throw reject("parameter budget exceeded");
        for (var entry : parameters.entrySet()) {
            validName(entry.getKey());
            Type type = entry.getValue();
            if (type == null || type.kind() == TypeKind.VOID || type.kind() == TypeKind.FUTURE)
                throw reject("invalid parameter type");
        }
        return new Builder(Map.copyOf(parameters)).build(root);
    }

    private static final class Builder {
        private final Map<String, Type> parameters;
        private final List<Step> steps = new ArrayList<>();
        private final EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        private final HashSet<String> takenParameters = new HashSet<>();
        private int deviceKernels;
        private int taskSubmissions;

        private Builder(Map<String, Type> parameters) { this.parameters = parameters; }

        private Value emit(Opcode op, Domain domain, Type type, int left, int right,
                           String detail, Long literal) {
            if (steps.size() >= 2048) throw reject("graph node budget exceeded");
            int id = steps.size();
            LinkedHashSet<Integer> deps = new LinkedHashSet<>();
            if (left >= 0) deps.add(left);
            if (right >= 0) deps.add(right);
            if (id > 0) deps.add(id - 1); // conservative control ordering
            for (int dependency : deps)
                if (dependency < 0 || dependency >= id) throw reject("bad dependency");
            steps.add(new Step(id, op, domain, type, List.copyOf(deps),
                    left, right, detail, literal));
            return new Value(type, id);
        }
        private Value emit(Opcode op, Domain domain, Type type, int left, int right) {
            return emit(op, domain, type, left, right, null, null);
        }

        private Plan build(Expr root) {
            Value result = walk(root, Domain.CPU, 0);
            return new Plan(steps, result.type(), result.id(), List.copyOf(effects),
                    deviceKernels, taskSubmissions);
        }

        private Type infer(Expr expr, Domain domain, int depth) {
            if (depth > 64) throw reject("AST depth exceeded");
            if (expr instanceof I64Literal) {
                if (domain != Domain.CPU) throw reject("host scalar inside GPU region");
                return I64;
            }
            if (expr instanceof Parameter parameter) {
                if (!Objects.equals(parameters.get(parameter.name()), parameter.type()))
                    throw reject("undeclared/mistyped parameter");
                return parameter.type();
            }
            if (expr instanceof Add add) {
                if (domain != Domain.CPU) throw reject("host arithmetic inside GPU region");
                if (!I64.equals(infer(add.left(), domain, depth + 1))
                        || !I64.equals(infer(add.right(), domain, depth + 1)))
                    throw reject("Add requires i64 operands");
                return I64;
            }
            if (expr instanceof Upload upload) {
                if (domain != Domain.CPU || !HOST_F32.equals(infer(upload.value(), domain, depth + 1)))
                    throw reject("upload residency mismatch");
                return GPU_F32;
            }
            if (expr instanceof Download download) {
                if (domain != Domain.CPU || !GPU_F32.equals(infer(download.value(), domain, depth + 1)))
                    throw reject("download residency mismatch");
                return HOST_F32;
            }
            if (expr instanceof DeviceKernel kernel) {
                if (domain != Domain.GPU || !GPU_F32.equals(infer(kernel.value(), domain, depth + 1)))
                    throw reject("device input residency mismatch");
                return GPU_F32;
            }
            if (expr instanceof Await await) {
                if (domain != Domain.CPU) throw reject("await requires host continuation");
                Type type = infer(await.value(), domain, depth + 1);
                if (type.kind() != TypeKind.FUTURE) throw reject("await requires Future<T>");
                return type.inner();
            }
            if (expr instanceof Sequence sequence) {
                Type last = VOID;
                for (Expr value : sequence.values()) last = infer(value, domain, depth + 1);
                return last;
            }
            if (expr instanceof CpuRegion cpu) {
                Type body = infer(cpu.body(), Domain.CPU, depth + 1);
                return cpu.mode() == LaunchMode.TASK ? Type.future(body) : body;
            }
            if (expr instanceof GpuRegion gpu) {
                Type body = infer(gpu.body(), Domain.GPU, depth + 1);
                if (!containsKernel(gpu.body(), 0)) throw reject("empty GPU region, no kernel work");
                return gpu.mode() == LaunchMode.TASK ? Type.future(body) : body;
            }
            throw reject("unknown AST node");
        }

        private boolean containsKernel(Expr expr, int depth) {
            if (depth > 64) throw reject("AST depth exceeded");
            if (expr instanceof DeviceKernel) return true;
            if (expr instanceof Sequence sequence)
                return sequence.values().stream().anyMatch(value -> containsKernel(value, depth + 1));
            if (expr instanceof CpuRegion cpu) return containsKernel(cpu.body(), depth + 1);
            if (expr instanceof GpuRegion gpu) return containsKernel(gpu.body(), depth + 1);
            if (expr instanceof Await await) return containsKernel(await.value(), depth + 1);
            if (expr instanceof Add add)
                return containsKernel(add.left(), depth + 1) || containsKernel(add.right(), depth + 1);
            if (expr instanceof Upload upload) return containsKernel(upload.value(), depth + 1);
            if (expr instanceof Download download) return containsKernel(download.value(), depth + 1);
            return false;
        }

        private Value walk(Expr expr, Domain domain, int depth) {
            if (depth > 64) throw reject("AST depth exceeded");
            if (expr instanceof I64Literal literal) {
                if (domain != Domain.CPU) throw reject("host scalar inside GPU region");
                effects.add(Effect.HOST);
                return emit(Opcode.CONST_I64, Domain.CPU, I64, -1, -1, null, literal.value());
            }
            if (expr instanceof Parameter parameter) {
                if (!Objects.equals(parameters.get(parameter.name()), parameter.type()))
                    throw reject("undeclared/mistyped parameter");
                if ((parameter.type().equals(HOST_F32) || parameter.type().equals(GPU_F32))
                        && !takenParameters.add(parameter.name()))
                    throw reject("use of moved owned buffer parameter '" + parameter.name() + "'");
                return emit(Opcode.PARAM, Domain.HOST, parameter.type(), -1, -1,
                        parameter.name(), null);
            }
            if (expr instanceof Add add) {
                if (domain != Domain.CPU) throw reject("host arithmetic inside GPU region");
                Value left = walk(add.left(), domain, depth + 1);
                Value right = walk(add.right(), domain, depth + 1);
                if (!I64.equals(left.type()) || !I64.equals(right.type()))
                    throw reject("Add requires i64 operands");
                effects.add(Effect.HOST);
                return emit(Opcode.ADD_I64, Domain.CPU, I64, left.id(), right.id());
            }
            if (expr instanceof Upload upload) {
                if (domain != Domain.CPU) throw reject("upload must be a host continuation");
                Value value = walk(upload.value(), domain, depth + 1);
                if (!HOST_F32.equals(value.type())) throw reject("upload residency mismatch");
                effects.add(Effect.TRANSFER);
                effects.add(Effect.SUSPEND);
                return emit(Opcode.UPLOAD, Domain.TRANSFER, GPU_F32, value.id(), -1);
            }
            if (expr instanceof Download download) {
                if (domain != Domain.CPU) throw reject("download must be a host continuation");
                Value value = walk(download.value(), domain, depth + 1);
                if (!GPU_F32.equals(value.type())) throw reject("download residency mismatch");
                effects.add(Effect.TRANSFER);
                effects.add(Effect.SUSPEND);
                return emit(Opcode.DOWNLOAD, Domain.TRANSFER, HOST_F32, value.id(), -1);
            }
            if (expr instanceof DeviceKernel kernel) {
                if (domain != Domain.GPU) throw reject("device kernel outside GPU region");
                Value value = walk(kernel.value(), domain, depth + 1);
                if (!GPU_F32.equals(value.type())) throw reject("device input residency mismatch");
                effects.add(Effect.DEVICE);
                effects.add(Effect.SUSPEND);
                deviceKernels++;
                return emit(Opcode.DEVICE_KERNEL, Domain.GPU, GPU_F32, value.id(), -1,
                        kernel.name(), null);
            }
            if (expr instanceof Await await) {
                if (domain != Domain.CPU) throw reject("await requires host continuation");
                Value future = walk(await.value(), domain, depth + 1);
                if (future.type().kind() != TypeKind.FUTURE)
                    throw reject("await requires Future<T>");
                effects.add(Effect.SUSPEND);
                return emit(Opcode.AWAIT, Domain.CPU, future.type().inner(), future.id(), -1);
            }
            if (expr instanceof Sequence sequence) {
                Value last = new Value(VOID, -1);
                for (Expr value : sequence.values()) last = walk(value, domain, depth + 1);
                return last;
            }
            if (expr instanceof CpuRegion cpu)
                return region(cpu.body(), cpu.mode(), Domain.CPU, domain, depth);
            if (expr instanceof GpuRegion gpu)
                return region(gpu.body(), gpu.mode(), Domain.GPU, domain, depth);
            throw reject("unknown AST node");
        }

        private Value region(Expr body, LaunchMode mode, Domain next, Domain prior, int depth) {
            Type bodyType = infer(body, next, depth + 1);
            if (next == Domain.GPU && !containsKernel(body, depth + 1))
                throw reject("empty GPU region, no kernel work");

            Value submitted = null;
            if (mode == LaunchMode.TASK) {
                effects.add(Effect.SUBMIT);
                taskSubmissions++;
                submitted = emit(Opcode.TASK_SUBMIT, Domain.HOST, Type.future(bodyType),
                        -1, -1, next.name(), null);
            }

            if (prior == Domain.GPU && next == Domain.CPU) {
                effects.add(Effect.SUSPEND);
                emit(Opcode.DEVICE_FENCE, Domain.TRANSFER, VOID, -1, -1);
            }
            if (prior == Domain.CPU && next == Domain.GPU) {
                effects.add(Effect.SUSPEND);
                emit(Opcode.DEVICE_ADMISSION, Domain.TRANSFER, VOID, -1, -1);
            }

            Value completed = walk(body, next, depth + 1);
            if (!completed.type().equals(bodyType))
                throw reject("inference/lowering type disagreement");

            if (mode == LaunchMode.TASK) {
                emit(Opcode.TASK_COMPLETE, Domain.HOST, VOID, submitted.id(), completed.id());
                return submitted;
            }
            return completed;
        }
    }

    /** Pure bounded synchronous host-i64 oracle. Never runs Task/GPU/transfer work on CPU. */
    public static long executeCpuI64(Plan plan) {
        Objects.requireNonNull(plan);
        if (!I64.equals(plan.resultType())) throw reject("CPU oracle requires i64");
        List<Step> steps = plan.steps();
        if (steps.size() > 2048 || plan.returnId() < 0 || plan.returnId() >= steps.size())
            throw reject("bad return node");
        long[] values = new long[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.id() != i || step.domain() != Domain.CPU)
                throw reject("no GPU/transfer/task execution provider");
            for (int dependency : step.dependencies())
                if (dependency < 0 || dependency >= i) throw reject("malformed dependency");
            switch (step.opcode()) {
                case CONST_I64 -> {
                    if (!I64.equals(step.type()) || step.literal() == null)
                        throw reject("malformed i64 constant");
                    values[i] = step.literal();
                }
                case ADD_I64 -> {
                    if (step.left() < 0 || step.right() < 0 || step.left() >= i
                            || step.right() >= i || !step.dependencies().contains(step.left())
                            || !step.dependencies().contains(step.right())
                            || !I64.equals(steps.get(step.left()).type())
                            || !I64.equals(steps.get(step.right()).type()))
                        throw reject("malformed i64 operands");
                    values[i] = Math.addExact(values[step.left()], values[step.right()]);
                }
                default -> throw reject("CPU oracle cannot execute " + step.opcode());
            }
        }
        return values[plan.returnId()];
    }
}
