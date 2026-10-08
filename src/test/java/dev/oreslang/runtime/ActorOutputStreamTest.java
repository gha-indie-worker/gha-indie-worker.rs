package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorOutputStreamTest {

    private static final class MutableOutputBox {
        int value = 1;
    }

    @Test
    void privateAndSharedActorsPublishCopiedFrozenOrderedData() {
        for (ActorRuntime.ActorKind kind :
                List.of(ActorRuntime.ActorKind.PRIVATE, ActorRuntime.ActorKind.SHARED)) {
            try (ActorRuntime runtime = new ActorRuntime()) {
                ActorRuntime.ActorRef<String> ref = switch (kind) {
                    case PRIVATE -> runtime.spawnPrivate(
                            context -> (message, turn) -> {
                                ArrayList<String> mutable = new ArrayList<>();
                                mutable.add("one");
                                turn.runtime().emitCurrentActorOutput(mutable);
                                mutable.add("mutated-after-send");
                                turn.runtime().emitCurrentActorOutput("two");
                                turn.runtime().endCurrentActor();
                            });
                    case SHARED -> runtime.spawnShared(
                            context -> (message, turn) -> {
                                ArrayList<String> mutable = new ArrayList<>();
                                mutable.add("one");
                                turn.runtime().emitCurrentActorOutput(mutable);
                                mutable.add("mutated-after-send");
                                turn.runtime().emitCurrentActorOutput("two");
                                turn.runtime().endCurrentActor();
                            });
                    case UNTRUSTED -> throw new AssertionError("not part of this case");
                };

                ref.ready().join();
                ref.send("go");

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> first =
                        ref.outputs().next().join();
                assertFalse(first.done());
                assertEquals(ref.id(), first.value().actorId());
                assertEquals(0L, first.value().sequence());
                assertEquals(List.of("one"), first.value().value(),
                        "output transport must snapshot before later actor mutation");

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> second =
                        ref.outputs().next().join();
                assertFalse(second.done());
                assertEquals(1L, second.value().sequence());
                assertEquals("two", second.value().value());

                ref.done().join();

                GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> terminal =
                        ref.outputs().next().join();
                assertTrue(terminal.done());
                assertNull(terminal.value());
                assertTrue(ref.outputs().isDone());
            }
        }
    }

    @Test
    void outputStreamRejectsLiveActorCapabilitiesAtRuntimeBoundary() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) ->
                            turn.runtime().emitCurrentActorOutput(turn.self()));

            ref.ready().join();
            ref.send("go");

            CompletionException failed =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            assertTrue(
                    failed.getCause().getMessage().contains("data values only"),
                    failed.getCause().getMessage());

            CompletionException outputFailure =
                    assertThrows(
                            CompletionException.class,
                            () -> ref.outputs().next().join());
            assertSame(failed.getCause(), outputFailure.getCause());
        }
    }

    @Test
    void outputStreamIsActorProducedAndReadOnlyByApiShape() {
        assertDoesNotThrow(() ->
                ActorRuntime.ActorOutputStream.class.getMethod("next"));
        assertThrows(
                NoSuchMethodException.class,
                () -> ActorRuntime.ActorOutputStream.class.getMethod(
                        "write", Object.class));
        assertThrows(
                NoSuchMethodException.class,
                () -> ActorRuntime.ActorOutputStream.class.getMethod(
                        "send", Object.class));
    }

    @Test
    void outputStreamRejectsUnknownMutableObjectIdentity() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ref = runtime.spawnShared(
                    context -> (message, turn) ->
                            turn.runtime().emitCurrentActorOutput(
                                    new MutableOutputBox()));

            ref.ready().join();
            ref.send("go");

            CompletionException failed =
                    assertThrows(CompletionException.class, () -> ref.done().join());
            assertInstanceOf(IllegalArgumentException.class, failed.getCause());
            assertTrue(
                    failed.getCause().getMessage().contains("not Sendable"),
                    failed.getCause().getMessage());

            CompletionException outputFailure =
                    assertThrows(
                            CompletionException.class,
                            () -> ref.outputs().next().join());
            assertSame(failed.getCause(), outputFailure.getCause());
        }
    }

}
