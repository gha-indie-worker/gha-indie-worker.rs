package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSharedCodeImageWhitelistTest {

    private static final class CodeAwareFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            SharedCodeImageStore.CodeImage image =
                    context.codeImage().orElseThrow(
                            () -> new AssertionError("expected explicit actor code image"));
            assertEquals("app/main.ores", image.codeUnitId());
            return new StopBehavior();
        }
    }

    private static final class NoCodeFactory
            implements ActorRuntime.BehaviorFactory<String> {
        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            assertTrue(context.codeImage().isEmpty(),
                    "ordinary actors must not receive ambient code-store access");
            return new StopBehavior();
        }
    }

    private static final class StopBehavior
            implements ActorRuntime.Behavior<String> {
        @Override
        public void onMessage(
                String message,
                ActorRuntime.ActorContext<String> context) {
            context.self().stop();
        }
    }

    @Test
    void oneWhitelistedCodeImageIsSharedByIdentityAcrossActorKinds() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", parsedOnce);

            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                ActorRuntime.ActorRef<String> ref =
                        runtime.spawnWithCodeImage(
                                kind,
                                IsolatePolicy.developer(),
                                image,
                                new CodeAwareFactory());

                Optional<SharedCodeImageStore.CodeImage> attached =
                        runtime.codeImageFor(ref);
                assertTrue(attached.isPresent(), "actor kind " + kind);
                assertSame(image, attached.orElseThrow(), "actor kind " + kind);
                assertSame(parsedOnce, attached.orElseThrow().program(),
                        "actor kind " + kind);

                ref.send("stop");
                assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS),
                        "actor kind " + kind);
                assertTrue(ref.failure().isEmpty(), "actor kind " + kind);
            }

            assertEquals(1, store.imageCount(),
                    "actors must not publish per-actor code copies");
        }
    }

    @Test
    void ordinaryActorsCannotEnumerateOrSeeUnattachedCodeImages() throws Exception {
        Ast.Program parsedOnce =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            store.publish("hidden/main.ores", parsedOnce);

            ActorRuntime.ActorRef<String> ref =
                    runtime.spawnPrivate(new NoCodeFactory());
            assertTrue(runtime.codeImageFor(ref).isEmpty());

            ref.send("stop");
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
        }
    }
}
