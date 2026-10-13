package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSpawnExecutionTest {

    @Test
    void spawnRunsOnStartReceiveAndDoneAcrossActorKinds() throws Exception {
        String program = """
                define actor SharedWorker as
                  let int started;

                  on_start(): void {
                    self.started = 1;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if self.started != 1; do
                      return;
                    fi
                    if mail.value == "stop"; do
                      self.end();
                    fi
                    return;
                  }
                end

                define isoactor PrivateWorker as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                define untrusted actor SandboxedWorker as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val shared = spawn SharedWorker();
                  val privateWorker = spawn PrivateWorker();
                  val sandboxed = spawn SandboxedWorker();

                  await shared.get_ready();
                  await privateWorker.get_ready();
                  await sandboxed.get_ready();

                  shared.send("stop");
                  privateWorker.send("stop");
                  sandboxed.send("stop");

                  await shared.get_done();
                  await privateWorker.get_done();
                  await sandboxed.get_done();

                  stdio.stdout.write("actors-done");
                  return;
                }
                """;

        assertEquals("actors-done", run(program, "source-actor-spawn.ores"));
    }

    @Test
    void sharedActorsReuseCodeWithoutSharingMutableActorState() throws Exception {
        String program = """
                define actor Counter as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.count = self.count + 1;
                    self.send(self.count);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val left = spawn Counter();
                  val right = spawn Counter();

                  await left.get_ready();
                  await right.get_ready();

                  left.send("go");
                  right.send("go");

                  val leftOut = (await left.outputs.next()).value.unwrap();
                  val rightOut = (await right.outputs.next()).value.unwrap();

                  stdio.stdout.write(leftOut.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(rightOut.value);

                  await left.get_done();
                  await right.get_done();
                  return;
                }
                """;

        assertEquals(
                "1|1",
                run(program, "source-shared-code-distinct-state.ores"),
                "actors may share immutable code identity but never writable actor fields");
    }

    @Test
    void readyFailsWhenOnStartLeavesActorStateUninitialized() throws Exception {
        String program = """
                define actor Worker as
                  val int required;

                  on_start(): void {
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  return;
                }
                """;

        PolyglotException failure =
                assertThrows(
                        PolyglotException.class,
                        () -> run(program, "source-actor-startup-failure.ores"));
        assertTrue(
                failure.getMessage().contains("did not initialize field 'required'"),
                failure.getMessage());
    }

    @Test
    void suspendingReceiveResumesBeforeLaterUserMail() throws Exception {
        String program = """
                define actor Worker as
                  let int phase;

                  on_start(): void {
                    self.phase = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
                      self.phase = 1;
                      self.send("before");
                      rt cooperate;

                      if self.phase != 1; do
                        self.send("interleaved");
                        return;
                      fi

                      self.phase = 2;
                      self.send("after");
                      return;
                    fi

                    if mail.value == "second"; do
                      if self.phase == 2; do
                        self.send("second");
                      else
                        self.send("interleaved");
                      fi
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();

                  // Queue both messages before the first receive cooperates.
                  worker.send("first");
                  worker.send("second");

                  val a = (await worker.outputs.next()).value.unwrap();
                  val b = (await worker.outputs.next()).value.unwrap();
                  val c = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.value);

                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "before|after|second",
                run(program, "source-actor-suspending-receive.ores"));
    }

    @Test
    void awaitSpawnIsAReadinessBarrierThatReturnsTheActorRef() throws Exception {
        String program = """
                define actor WorkerActor as
                  let int initialized;

                  on_start(): void {
                    self.initialized = 1;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if self.initialized == 1; do
                      self.send("ready");
                    fi
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn WorkerActor();
                  worker.send("run");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.get_done();
                  return;
                }
                """;
        assertEquals("ready", run(program, "source-await-spawn-ref.ores"));
    }

    @Test
    void awaitSpawnFailsWhenOnStartFails() throws Exception {
        String program = """
                define actor WorkerActor as
                  val int required;

                  on_start(): void {
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn WorkerActor();
                  worker.send("unreachable");
                  return;
                }
                """;
        PolyglotException error = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-await-spawn-failure.ores"));
        assertTrue(error.getMessage().contains("did not initialize field 'required'"),
                error.getMessage());
    }

    @Test
    void actorLifecycleMethodsReturnAwaitableFuturesAndRejectPropertyAccess() throws Exception {
        String valid = """
                define actor Worker as
                  receive(ActorMail<String> message): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  val initialized = worker.get_ready();
                  await initialized;
                  worker.send("finish");
                  val finalized = worker.get_done();
                  await finalized;
                  stdio.stdout.write("finished");
                  return;
                }
                """;
        assertEquals("finished", run(valid, "source-actor-future-getters.ores"));

        for (String forbidden : java.util.List.of(
                "await worker.ready;",
                "await worker.done;",
                "await worker.get_ready(1);",
                "await worker.get_done(1);",
                "await worker.get_ready;",
                "await worker.get_done;")) {
            String source = """
                    define actor Worker as
                      receive(ActorMail<String> message): void {
                        self.end();
                        return;
                      }
                    end
                    pub async routine main(): void {
                      val worker = spawn Worker();
                      """ + forbidden + """
                      
                      worker.stop();
                      return;
                    }
                    """;
            assertThrows(PolyglotException.class,
                    () -> run(source, "source-actor-bad-lifecycle-access.ores"),
                    forbidden + " must not be accepted");
        }
    }

    @Test
    void sourceActorMailboxUsesCompilerBoundBinaryMessagePackForListAndTuple() throws Exception {
        String source = """
                define actor ListWorker as
                  receive(ActorMail<List<string>> mail): void {
                    if mail.value[0] == "from-list"; do
                      self.send("list-ok");
                    else
                      self.send("list-wrong");
                    fi
                    self.end();
                    return;
                  }
                end

                define isoactor TupleWorker as
                  receive(ActorMail<Tuple[bool, string]> mail): void {
                    if mail.value[1] == "from-tuple"; do
                      self.send("tuple-ok");
                    else
                      self.send("tuple-wrong");
                    fi
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val listWorker = spawn ListWorker();
                  val tupleWorker = spawn TupleWorker();
                  await listWorker.get_ready();
                  await tupleWorker.get_ready();

                  listWorker.send(["from-list"]);
                  tupleWorker.send((true, "from-tuple"));

                  val a = (await listWorker.outputs.next()).value.unwrap();
                  val b = (await tupleWorker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.value);
                  await listWorker.get_done();
                  await tupleWorker.get_done();
                  return;
                }
                """;
        assertEquals("list-ok|tuple-ok",
                run(source, "source-actor-msgpack-v2-collections.ores"));
    }

    @Test
    void taggedEnumPatternMessagesCrossActorBoundaryInBinary() throws Exception {
        String source = """
                define enum Status as
                  Message1: Tuple[bool, string] = 1
                  Message2: string = 2
                  Ready = 3
                end

                define pattern Messages as
                  Status.Message1
                  Status.Message2
                end

                define isoactor Worker as
                  receive(ActorMail<Messages> mail): void {
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("hello" as Status.Message2);
                  await worker.get_done();
                  stdio.stdout.write("tagged-done");
                  return;
                }
                """;
        assertEquals("tagged-done", run(source, "source-actor-nominal-tags.ores"));
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source =
                Source.newBuilder(OresLanguage.ID, sourceText, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build();

        try (Context context =
                Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .out(output)
                        .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
