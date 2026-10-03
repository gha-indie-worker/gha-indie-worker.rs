package dev.oreslang.runtime;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class OresContext implements AutoCloseable {
    private static final ContextReference<OresContext> REFERENCE = ContextReference.create(OresLanguage.class);

    private final OresLanguage language;
    private final TruffleLanguage.Env env;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ActorRuntime actors;
    private final RuntimeGarbageCollector garbageCollector;
    private final UUID contextId = UUID.randomUUID();
    private final AtomicLong schedulerSafepoints = new AtomicLong();
    private final IsolatePolicy isolatePolicy;
    private final ExecutionProfile executionProfile;
    private final ReentrantLock adversarialActorTurnLock = new ReentrantLock(true);
    private final Map<String, Object> linkedCodeUnits = new HashMap<>();\n    private final Set<AutoCloseable> nativeResources = ConcurrentHashMap.newKeySet();

    public OresContext(OresLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.input = new BufferedReader(new InputStreamReader(env.in()));
        this.output = new PrintWriter(env.out(), true);
        this.isolatePolicy = IsolatePolicy.fromApplicationArguments(env.getApplicationArguments());
        this.executionProfile = IsolatePolicy.executionProfileFromApplicationArguments(env.getApplicationArguments());
        this.actors = new ActorRuntime(
                isolatePolicy,
                ActorRuntime.DispatcherConfig.defaults(),
                this::executeActorTurn);
        this.garbageCollector = new RuntimeGarbageCollector();
        this.actors.setActorExitHook(garbageCollector::retireActorDomain);
    }

    public static OresContext get(Node node) {
        return REFERENCE.get(node);
    }

    public OresLanguage language() { return language; }
    public TruffleLanguage.Env env() { return env; }
    public BufferedReader input() { return input; }
    public PrintWriter output() { return output; }
    public ActorRuntime actors() { return actors; }
    public RuntimeGarbageCollector garbageCollector() { return garbageCollector; }
    public UUID contextId() { return contextId; }
    public IsolatePolicy isolatePolicy() { return isolatePolicy; }
    public ExecutionProfile executionProfile() { return executionProfile; }

    public Object lookupHostSymbol(String className) {
        requireCapability(IsolatePolicy.Capability.JAVA_INTEROP, "Java host import " + className);
        if (!env.isHostLookupAllowed()) {
            throw new SecurityException("Java host class lookup is disabled by the embedding Context");
        }
        try {
            return env.lookupHostSymbol(className);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Java host class is not allowlisted or unavailable: " + className,
                    failure);
        }
    }

    public void requireCapability(IsolatePolicy.Capability capability, String api) {
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null && ActorRuntime.currentActorRuntime() != actors) {
            throw new SecurityException(
                    "actor capability check crossed ActorRuntime boundary for " + api);
        }
        requireEffectiveCapability(isolatePolicy, capability, api);
    }

    static void requireEffectiveCapability(
            IsolatePolicy contextPolicy,
            IsolatePolicy.Capability capability,
            String api) {
        // Actor turns execute inside the parent Truffle context, but they may
        // have a strictly narrower capability set than that context. Always
        // enforce the actor-local policy first so helper functions, imported
        // code, and ordinary class methods cannot launder authority from the
        // parent context into a private actor.
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) actorPolicy.require(capability, api);
        contextPolicy.require(capability, api);
    }

    /**
     * Compiler-injected cooperative scheduling checkpoint. Loops call this on
     * every iteration so a future supervisor/control mailbox can interrupt
     * long-running actor code without requiring recursion-only looping.
     */
    public void schedulerSafepoint() {
        schedulerSafepoints.incrementAndGet();
        actors.schedulerSafepoint();
    }

    public long schedulerSafepoints() { return schedulerSafepoints.get(); }

    public void registerNativeResource(AutoCloseable resource) {
        nativeResources.add(resource);
    }

    public void unregisterNativeResource(AutoCloseable resource) {
        nativeResources.remove(resource);
    }

    /**
     * Host-managed cross-file link registry. Guest imports may only observe
     * units that the host has explicitly loaded into this context; import
     * syntax never grants filesystem access.
     */
    public synchronized void registerLinkedCodeUnit(String codeUnitId, Object unit) {
        if (codeUnitId == null || codeUnitId.isBlank()) {
            throw new IllegalArgumentException("linked code unit id cannot be blank");
        }
        Object previous = linkedCodeUnits.putIfAbsent(codeUnitId, unit);
        if (previous != null && previous != unit) {
            throw new IllegalStateException("code unit already linked in this context: " + codeUnitId);
        }
    }

    public synchronized Object linkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.get(codeUnitId);
    }

    public synchronized boolean hasLinkedCodeUnit(String codeUnitId) {
        return linkedCodeUnits.containsKey(codeUnitId);
    }

    private void executeActorTurn(Runnable turn) {
        boolean serialize = isolatePolicy.adversarial();
        if (serialize) adversarialActorTurnLock.lock();
        TruffleContext truffleContext = env.getContext();
        Object previous = null;
        boolean entered = false;
        try {
            previous = truffleContext.enter(null);
            entered = true;
            turn.run();
        } finally {
            if (entered) truffleContext.leave(null, previous);
            if (serialize) adversarialActorTurnLock.unlock();
        }
    }


    public Map<String, Object> processDescriptor() {
        return Map.of(
                "context_id", contextId.toString(),
                "runtime", "graalvm-truffle",
                "language", "oreslang",
                "execution_mode", executionProfile.mode().name(),
                "platform", executionProfile.platform().name(),
                "scheduler_safepoints", schedulerSafepoints.get());
    }

    @Override
    public void close() {
        try {
            actors.close();
        } finally {
            synchronized (this) {
                linkedCodeUnits.clear();
            }
            AutoCloseable[] resources = nativeResources.toArray(AutoCloseable[]::new);
            for (AutoCloseable resource : resources) {
                try { resource.close(); }
                catch (Exception ignored) { }
            }
            nativeResources.clear();
            garbageCollector.close();
            output.flush();
        }
    }
}
