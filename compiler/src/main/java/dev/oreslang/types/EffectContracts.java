package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative, closed-world callable effect analysis.
 *
 * This is a compiler building block, not a runtime effect handler. Unknown calls
 * are effects, and a verified trap callable handles only an ordinary THROW, never panic,
 * raise, cancellation, native failure, or an actor supervision event.
 */
public final class EffectContracts {
    private EffectContracts() { }

    public enum Effect {
        AMBIENT_READ, EXTERNAL_WRITE, IO, ENVIRONMENT, CLOCK, RANDOM,
        ACTOR, SCHEDULER, SYNCHRONIZATION, FFI_UNKNOWN, UNKNOWN_CALL,
        THROW, RAISE, PANIC, CANCELLATION
    }

    /** Pure is an observational guarantee, not a no-throw/no-panic guarantee. */
    public static Set<Effect> pureAllowance() {
        return Set.copyOf(EnumSet.of(Effect.THROW, Effect.RAISE,
                Effect.PANIC, Effect.CANCELLATION));
    }

    public record Call(String target) {
        public Call {
            Objects.requireNonNull(target, "target");
            if (target.isBlank() || target.length() > 256) {
                throw new IllegalArgumentException("invalid effect call target");
            }
        }
        public static Call direct(String target) { return new Call(target); }
    }

    public record Callable(String symbol, Set<Effect> direct, List<Call> calls, Set<Effect> allowed, boolean trapsThrow) {
        public Callable {
            Objects.requireNonNull(symbol, "symbol");
            if (symbol.isBlank() || symbol.length() > 256) {
                throw new IllegalArgumentException("invalid callable symbol");
            }
            direct = Set.copyOf(direct);
            calls = List.copyOf(calls);
            allowed = allowed == null ? null : Set.copyOf(allowed);
        }
        public Callable(String symbol, Set<Effect> direct, List<Call> calls, Set<Effect> allowed) {
            this(symbol, direct, calls, allowed, false);
        }
        public static Callable trapping(String symbol, Set<Effect> effects, List<Call> calls) {
            return new Callable(symbol, effects, calls, null, true);
        }
        public static Callable inferred(String symbol, Set<Effect> effects, List<Call> calls) {
            return new Callable(symbol, effects, calls, null);
        }
        public static Callable pure(String symbol, Set<Effect> effects, List<Call> calls) {
            return new Callable(symbol, effects, calls, pureAllowance());
        }
    }

    /** Callers use qualified, overload-resolved symbols, never simple textual names. */
    public static final class Graph {
        private static final int MAX_CALLABLES = 1024;
        private static final int MAX_EDGES = 16384;
        private static final int MAX_EDGES_PER_CALLABLE = 1024;
        private final Map<String, Callable> definitions = new LinkedHashMap<>();
        private int edgeCount;

        public void add(Callable callable) {
            Objects.requireNonNull(callable, "callable");
            if (definitions.containsKey(callable.symbol())) {
                throw new IllegalArgumentException("duplicate effect symbol: " + callable.symbol());
            }
            // Reject pathological graphs before inserting any part of them.
            if (definitions.size() >= MAX_CALLABLES
                    || callable.calls().size() > MAX_EDGES_PER_CALLABLE
                    || callable.calls().size() > MAX_EDGES - edgeCount) {
                throw new IllegalArgumentException("effect graph exceeds admission budget");
            }
            definitions.put(callable.symbol(), callable);
            edgeCount += callable.calls().size();
        }

        public Map<String, Set<Effect>> infer() {
            Map<String, Set<Effect>> results = new LinkedHashMap<>();
            for (Callable def : definitions.values()) {
                results.put(def.symbol(), enumCopy(def.direct()));
            }
            // Finite monotonic fixed point works for mutually recursive SCCs as well.
            boolean changed;
            do {
                changed = false;
                for (Callable def : definitions.values()) {
                    EnumSet<Effect> acc = enumCopy(def.direct());
                    for (Call call : def.calls()) {
                        Set<Effect> from = results.get(call.target());
                        if (from == null) {
                            acc.add(Effect.UNKNOWN_CALL); // fail closed across unknown/imported/FFI targets
                        } else {
                            if (definitions.get(call.target()).trapsThrow()) {
                                for (Effect effect : from) if (effect != Effect.THROW) acc.add(effect);
                            } else {
                                acc.addAll(from);
                            }
                        }
                    }
                    if (!acc.equals(results.get(def.symbol()))) {
                        results.put(def.symbol(), acc);
                        changed = true;
                    }
                }
            } while (changed);
            Map<String, Set<Effect>> immutable = new LinkedHashMap<>();
            results.forEach((key, value) -> immutable.put(key, Collections.unmodifiableSet(enumCopy(value))));
            return Collections.unmodifiableMap(immutable);
        }

        public Map<String, Set<Effect>> validate() {
            Map<String, Set<Effect>> inferred = infer();
            for (Callable def : definitions.values()) {
                if (def.allowed() == null) continue;
                EnumSet<Effect> forbidden = enumCopy(inferred.get(def.symbol()));
                forbidden.removeAll(def.allowed());
                if (!forbidden.isEmpty()) {
                    Effect first = forbidden.iterator().next();
                    throw new IllegalArgumentException("effect contract violation in "
                            + def.symbol() + ": " + first + explainPath(def.symbol(), first, inferred));
                }
            }
            return inferred;
        }

        private String explainPath(String from, Effect wanted, Map<String, Set<Effect>> inferred) {
            // Parent links avoid quadratic path cloning in wide/deep call graphs.
            java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
            Map<String, String> parent = new LinkedHashMap<>();
            parent.put(from, null);
            pending.add(from);
            while (!pending.isEmpty()) {
                String symbol = pending.removeFirst();
                Callable def = definitions.get(symbol);
                if (def == null || def.direct().contains(wanted)) {
                    return " via " + formatPath(symbol, parent);
                }
                for (Call call : def.calls()) {
                    String target = call.target();
                    if (wanted == Effect.THROW && definitions.containsKey(target)
                            && definitions.get(target).trapsThrow()) continue;
                    if (wanted == Effect.UNKNOWN_CALL && !definitions.containsKey(target)) {
                        return " via " + formatPath(symbol, parent) + " -> " + target;
                    }
                    if (inferred.getOrDefault(target, Set.of()).contains(wanted)
                            && !parent.containsKey(target)) {
                        parent.put(target, symbol);
                        pending.addLast(target);
                    }
                }
            }
            return "";
        }

        private static String formatPath(String last, Map<String, String> parent) {
            java.util.ArrayDeque<String> reversed = new java.util.ArrayDeque<>();
            for (String cursor = last; cursor != null; cursor = parent.get(cursor)) {
                reversed.addFirst(cursor);
            }
            return String.join(" -> ", reversed);
        }

    }

    private static EnumSet<Effect> enumCopy(Set<Effect> source) {
        EnumSet<Effect> copy = EnumSet.noneOf(Effect.class);
        copy.addAll(source);
        return copy;
    }
}
