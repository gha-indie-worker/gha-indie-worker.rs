package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AsyncNativeIoTest {
    @TempDir Path root;

    private String run(String program, RuntimePermissions permissions, OutputStream output) throws Exception {
        Path source = root.resolve("test.ores");
        Files.writeString(source, program);
        LinkedProgramRunner.run(source, IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ, IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK), permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(), output, new ByteArrayOutputStream());
        return output.toString();
    }

    @Test void filesystemAwaitsReturnValuesAndPropagatePermissionFailures() throws Exception {
        String path = root.resolve("item.txt").toString();
        String output = run("""
            pub async routine main(): void {
              await fs.mkdir_all_async("%s/sub");
              await fs.write_text_async("%s", "hello");
              await fs.append_text_async("%s", " world");
              stdio.println(await fs.exists_async("%s"));
              stdio.println(await fs.read_text_async("%s"));
              await fs.remove_async("%s");
              stdio.println(await fs.exists_async("%s"));
              try { await fs.read_text_async("%s"); }
              catch (failure) { stdio.println("missing-caught"); }
              try { await fs.write_text_async("/tmp/ores-async-denied.txt", "blocked"); }
              catch (failure) { stdio.println("denied-caught"); }
              return;
            }
            """.formatted(root, path, path, path, path, path, path, path),
            RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream());
        assertEquals("true\nhello world\nfalse\nmissing-caught\ndenied-caught\n", output.replace("\r", ""));
        assertFalse(Files.exists(Path.of("/tmp/ores-async-denied.txt")));
    }

    @Test void untrustedActorCannotLaunderParentFilesystemCapabilityThroughWorker() throws Exception {
        Path path = root.resolve("blocked.txt");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
            define untrusted actor Worker as
              receive(ActorMail<String> mail): void {
                try {
                  val operation = fs.write_text_async(mail.value, "escaped");
                  self.send("unexpected-admission");
                } catch (failure) { self.send("denied"); }
                self.end(); return;
              }
            end
            pub async routine main(): void {
              val worker = spawn Worker(); await worker.ready;
              worker.send("%s");
              for await const output of worker.outputs { stdio.println(output.value); }
              await worker.done; return;
            }
            """.formatted(path), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("restricted capability"), failure.getMessage());
        assertFalse(Files.exists(path));
    }

    @Test void asyncCanonicalizationRejectsSymlinkEscape() throws Exception {
        Path permitted = Files.createDirectory(root.resolve("permitted"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(permitted.resolve("escape"), outside);
        String output = run("""
            pub async routine main(): void {
              try { await fs.write_text_async("%s/escape/secret.txt", "escaped"); }
              catch (failure) { stdio.println("denied"); }
              return;
            }
            """.formatted(permitted), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, permitted.toString()), new ByteArrayOutputStream());
        assertEquals("denied\n", output.replace("\r", ""));
        assertFalse(Files.exists(outside.resolve("secret.txt")));
    }

    @Test void boundedAdmissionNeverRunsOnCallerAndCloseSettlesPendingWork() throws Exception {
        try (var io = new AsyncNativeIo(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            var first = io.submit("slow", () -> {
                assertNotSame(caller, Thread.currentThread());
                entered.countDown(); hold.await(); return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var rejected = io.submit("overflow", () -> fail("must not execute"));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
            assertEquals(7, io.submitControl("close", () -> 7).get(1, TimeUnit.SECONDS),
                    "socket close must remain available when data admission is full");
            assertFalse(first.cancel(true), "mutations do not promise cancellable rollback");
            io.close();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertTrue(io.submit("closed", () -> 1).isDone());
        }
    }

    @Test void delayedTcpReadReleasesSingleActorCarrierAndSupportsWriteAndEof() throws Exception {
        String previous = System.getProperty("ores.runtime.actor.shared.parallelism");
        System.setProperty("ores.runtime.actor.shared.parallelism", "1");
        CountDownLatch probeRan = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                if (toString().contains("probe")) probeRan.countDown();
            }
        };
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peer = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peer.submit(() -> {
                try (var socket = server.accept()) {
                    assertTrue(probeRan.await(5, TimeUnit.SECONDS), "await must release the only actor carrier");
                    socket.getOutputStream().write("reply\n".getBytes());
                    socket.shutdownOutput();
                    assertEquals("ack", new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine());
                }
                return null;
            });
            String program = """
                define actor Probe as
                  receive(ActorMail<String> mail): void { stdio.println("probe"); self.end(); return; }
                end
                define actor Reader as
                  receive(ActorMail<String> mail): void {
                    val socket = await network.connect_async("127.0.0.1", %d);
                    val reading = socket.read_line_async();
                    self.send("reading");
                    val line = await reading;
                    stdio.println(line);
                    stdio.println(await socket.read_line_async());
                    await socket.write_text_async("ack\\n");
                    await socket.close_async();
                    self.end(); return;
                  }
                end
                pub async routine main(): void {
                  val reader = spawn Reader(); await reader.ready;
                  val probe = spawn Probe(); await probe.ready;
                  reader.send("go");
                  await reader.outputs.next();
                  probe.send("go");
                  await reader.done; await probe.done; return;
                }
                """.formatted(server.getLocalPort());
            run(program, RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + server.getLocalPort()), output);
            exchange.get(2, TimeUnit.SECONDS);
            assertTrue(output.toString().contains("reply"));
            assertTrue(output.toString().contains("None"));
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.actor.shared.parallelism");
            else System.setProperty("ores.runtime.actor.shared.parallelism", previous);
        }
    }

    @Test void httpClientAwaitsGetPostAndDoesNotFollowRedirects() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestMethod().equals("POST") ? exchange.getRequestBody().readAllBytes() : "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(302, 0); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String output = run("""
                pub async routine main(): void {
                  stdio.println(await http.get_text_async("%s/"));
                  stdio.println(await http.post_text_async("%s/", "posted"));
                  await http.get_text_async("%s/redirect");
                  stdio.println("redirect-not-followed");
                  return;
                }
                """.formatted(url, url, url), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getAddress().getPort()), new ByteArrayOutputStream());
            assertEquals("hello\nposted\nredirect-not-followed\n", output.replace("\r", ""));
        } finally { server.stop(0); }
    }
}
