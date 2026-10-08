package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SharedCodeImageStoreTest {

    @Test
    void sharedPrivateAndUntrustedReferencesObserveOneCodeImage() {
        Ast.Program parsedOnce = Parser.parse("""
                pub routine main(): void { return; }
                """);

        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            SharedCodeImageStore.CodeImage initial =
                    store.publish("app/main.ores", parsedOnce);
            assertSame(parsedOnce, initial.program());

            // These are logical actor-kind observers, not independent code
            // copies or claims about compiled native instructions/processes.
            for (ActorRuntime.ActorKind kind : ActorRuntime.ActorKind.values()) {
                SharedCodeImageStore.CodeImage observer = store.get("app/main.ores");
                assertSame(initial, observer, "actor kind " + kind);
                assertSame(parsedOnce, observer.program(), "actor kind " + kind);
            }
            assertSame(initial, store.publish("app/main.ores", parsedOnce));
            assertEquals(1, store.imageCount());
        }
    }

    @Test
    void concurrentReadersNeverConstructANewCodeImage() throws Exception {
        Ast.Program parsedOnce = Parser.parse("pub routine main(): void { return; }");
        try (SharedCodeImageStore store = new SharedCodeImageStore()) {
            SharedCodeImageStore.CodeImage expected =
                    store.publish("shared/main.ores", parsedOnce);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(4)) {
                List<java.util.concurrent.Future<SharedCodeImageStore.CodeImage>> tasks =
                        new ArrayList<>();
                for (int i = 0; i < 48; i++) {
                    tasks.add(pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return store.get("shared/main.ores");
                    }));
                }
                start.countDown();
                for (var task : tasks) {
                    assertSame(expected, task.get(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void cannotOverwriteActiveCodeUnitOrUseStoreAfterClose() {
        SharedCodeImageStore store = new SharedCodeImageStore();
        Ast.Program one = Parser.parse("pub routine main(): void { return; }");
        Ast.Program independentCopy = Parser.parse("pub routine main(): void { return; }");
        store.publish("same/main.ores", one);

        assertThrows(IllegalStateException.class,
                () -> store.publish("same/main.ores", independentCopy),
                "new generations require a new OresVM/code store");
        assertSame(one, store.get("same/main.ores").program());

        store.close();
        assertEquals(0, store.imageCount());
        assertThrows(IllegalStateException.class, () -> store.get("same/main.ores"));
        assertThrows(IllegalStateException.class,
                () -> store.publish("same/main.ores", one));
    }

    @Test
    void codeImagesCannotBeMintedOutsideTheStore() {
        assertEquals(
                0,
                SharedCodeImageStore.CodeImage.class.getConstructors().length,
                "CodeImage must remain an opaque store-minted capability");
    }


    @Test
    void parsedCodeImageAstContainersAreTransitivelyReadOnly() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  let int count = 0;

                  receive(ActorMail<String> mail): void {
                    self.count = self.count + 1;
                    self.end();
                    return;
                  }
                end
                """);

        Ast.ModuleDecl root = program.modules().getFirst();
        Ast.ClassDecl worker = root.declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.MethodDecl receive = worker.methods().stream()
                .filter(method -> method.name().equals("receive"))
                .findFirst()
                .orElseThrow();

        assertThrows(UnsupportedOperationException.class,
                () -> program.modules().add(root));
        assertThrows(UnsupportedOperationException.class,
                () -> root.declarations().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.fields().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> worker.methods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> receive.body().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> receive.parameters().clear());
    }

}
