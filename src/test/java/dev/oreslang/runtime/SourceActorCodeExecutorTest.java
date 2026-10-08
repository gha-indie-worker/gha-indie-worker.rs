package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorCodeExecutorTest {

    private static final class State implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private int messages;

        private State(ActorRuntime.ActorId owner) {
            this.owner = owner;
        }

        @Override
        public ActorRuntime.ActorId ownerActorId() {
            return owner;
        }
    }

    private record Executor(SharedCodeImageStore.CodeImage codeImage)
            implements ActorRuntime.ActorCodeExecutor {

        @Override
        public ActorRuntime.ActorOwnedGuestState initializeActor(
                String actorTypeName,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorTypeName);
            assertSame(codeImage, context.codeImage().orElseThrow());
            assertTrue(context.currentMail().isEmpty(),
                    "startup must execute before any mailbox message");
            return new State(context.self().id());
        }

        @Override
        public void receiveActor(
                String actorTypeName,
                ActorRuntime.ActorOwnedGuestState rawState,
                ActorRuntime.ActorInboxMail<Object> mail,
                ActorRuntime.ActorContext<Object> context) {
            assertEquals("Worker", actorTypeName);
            State state = assertInstanceOf(State.class, rawState);
            assertEquals(context.self().id(), state.ownerActorId());
            assertEquals(context.self().id(), mail.recipient());
            assertEquals(0L, mail.sequence());
            assertEquals("stop", mail.value());
            assertEquals(mail, context.currentMail().orElseThrow());
            state.messages++;
            assertEquals(1, state.messages);
            context.self().stop();
        }
    }

    @Test
    void sourceActorStateIsCreatedOnActorDomainAndReceivesTypedEnvelope()
            throws Exception {
        Ast.Program program =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", program);
            Executor executor = new Executor(image);

            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                ActorRuntime.ActorRef<Object> ref =
                        runtime.spawnCodeActor(
                                kind,
                                IsolatePolicy.developer(),
                                executor,
                                "Worker");

                assertSame(image, runtime.codeImageFor(ref).orElseThrow());
                assertNull(ref.ready().join());
                assertTrue(ref.isAlive());

                ref.send("stop");
                assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS),
                        "actor kind " + kind);
                assertTrue(ref.failure().isEmpty(), "actor kind " + kind);
                assertNull(ref.done().join());
            }

            assertEquals(1, store.imageCount(),
                    "source actors must reuse the exact shared code image");
        }
    }

    @Test
    void sourceActorReadyFailsWhenInitializedStateClaimsWrongOwner()
            throws Exception {
        Ast.Program program =
                Parser.parse("pub routine main(): void { return; }");

        try (SharedCodeImageStore store = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            SharedCodeImageStore.CodeImage image =
                    store.publish("app/main.ores", program);

            ActorRuntime.ActorCodeExecutor bad =
                    new ActorRuntime.ActorCodeExecutor() {
                        @Override
                        public SharedCodeImageStore.CodeImage codeImage() {
                            return image;
                        }

                        @Override
                        public ActorRuntime.ActorOwnedGuestState initializeActor(
                                String actorTypeName,
                                ActorRuntime.ActorContext<Object> context) {
                            ActorRuntime.ActorId wrong =
                                    ActorRuntime.ActorId.create();
                            return () -> wrong;
                        }

                        @Override
                        public void receiveActor(
                                String actorTypeName,
                                ActorRuntime.ActorOwnedGuestState state,
                                ActorRuntime.ActorInboxMail<Object> mail,
                                ActorRuntime.ActorContext<Object> context) {
                            fail("mailbox handler must not run after startup ownership failure");
                        }
                    };

            ActorRuntime.ActorRef<Object> ref =
                    runtime.spawnCodeActor(
                            ActorRuntime.ActorKind.PRIVATE,
                            bad,
                            "Worker");

            Throwable failure = assertThrows(
                    RuntimeException.class,
                    () -> ref.ready().join());
            assertTrue(failure.getMessage().contains("does not match actor")
                            || (failure.getCause() != null
                                && failure.getCause().getMessage().contains(
                                        "does not match actor")),
                    failure.toString());
            assertTrue(ref.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent());
        }
    }
}
