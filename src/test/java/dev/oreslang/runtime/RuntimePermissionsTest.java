package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimePermissionsTest {
    @Test
    void denyRuleOverridesBroadAllow() throws Exception {
        Path root = Files.createTempDirectory("ores-perm-root-");
        Path secret = Files.createDirectories(root.resolve("secret"));

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withDenied(RuntimePermissions.Permission.READ, secret.toString());

        assertTrue(permissions.allows(
                RuntimePermissions.Permission.READ,
                root.resolve("public.txt").toString()));
        assertFalse(permissions.allows(
                RuntimePermissions.Permission.READ,
                secret.resolve("token.txt").toString()));
    }

    @Test
    void networkHostGrantCanBeNarrowedByPortDeny() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "example.com")
                .withDenied(RuntimePermissions.Permission.NET, "example.com:22");

        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "example.com:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com:22"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "other.example:443"));
    }

    @Test
    void wildcardSubdomainDoesNotGrantBareParent() {
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "*.example.com");

        assertTrue(permissions.allows(RuntimePermissions.Permission.NET, "api.example.com:443"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.NET, "example.com:443"));
    }

    @Test
    void applicationArgumentsRoundTripScopedRules() throws Exception {
        Path root = Files.createTempDirectory("ores-perm-roundtrip-");
        RuntimePermissions original = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.ENV, "PUBLIC_*")
                .withDenied(RuntimePermissions.Permission.ENV, "PUBLIC_SECRET");

        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.ENVIRONMENT);

        RuntimePermissions restored = RuntimePermissions.fromApplicationArguments(
                original.applicationArguments(),
                policy);

        assertTrue(restored.allows(
                RuntimePermissions.Permission.READ,
                root.resolve("file.txt").toString()));
        assertTrue(restored.allows(RuntimePermissions.Permission.ENV, "PUBLIC_NAME"));
        assertFalse(restored.allows(RuntimePermissions.Permission.ENV, "PUBLIC_SECRET"));
    }

    @Test
    void compileModeRejectsForbiddenIoButRuntimeModeDefersIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val text = fs.read_text("/tmp/blocked.txt");
                  return;
                }
                """));

        SecurityException compileFailure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(
                        program,
                        IsolatePolicy.developer(),
                        PermissionCheckMode.COMPILE));
        assertTrue(compileFailure.getMessage().contains("FILESYSTEM_READ"));

        assertDoesNotThrow(() -> CapabilityChecker.check(
                program,
                IsolatePolicy.developer(),
                PermissionCheckMode.RUNTIME));
    }

    @Test
    void runtimeWriteSucceedsOnlyInsideGrantedRoot() throws Exception {
        Path root = Files.createTempDirectory("ores-native-write-").toRealPath();
        Path source = root.resolve("program.ores");
        Path allowed = root.resolve("allowed.txt");
        Path denied = Files.createTempFile("ores-native-denied-", ".txt").toRealPath();

        Files.writeString(source, """
                pub fnc main(): void {
                  fs.write_text("%s", "allowed");
                  return;
                }
                """.formatted(escape(allowed)));

        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_WRITE);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());

        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        assertEquals("allowed", Files.readString(allowed));

        Files.writeString(source, """
                pub fnc main(): void {
                  fs.write_text("%s", "blocked");
                  return;
                }
                """.formatted(escape(denied)));

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> LinkedProgramRunner.run(
                        source,
                        policy,
                        permissions,
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        System.getenv(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(deepMessage(failure).contains("permission denied"));
        assertNotEquals("blocked", Files.readString(denied));
    }

    @Test
    void checkModeArgumentDoesNotBecomeAPermissionGrant() {
        RuntimePermissions permissions = RuntimePermissions.fromApplicationArguments(
                new String[]{"--ores-permission-check=runtime", "--ores-permission-env-allow=PUBLIC_*"},
                IsolatePolicy.developer());
        assertTrue(permissions.allows(RuntimePermissions.Permission.ENV, "PUBLIC_NAME"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.ENV, "SECRET"));
        assertFalse(permissions.allows(RuntimePermissions.Permission.WRITE, "/tmp/out"));
    }

    @Test
    void nonexistentChildOfSymlinkResolvesBeforePermissionComparison() throws Exception {
        Path root = Files.createTempDirectory("ores-permission-links-");
        Path granted = Files.createDirectory(root.resolve("granted"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = Files.createSymbolicLink(root.resolve("alias"), granted);
        Path escape = Files.createSymbolicLink(granted.resolve("escape"), outside);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, granted.toString());
        assertTrue(permissions.allows(RuntimePermissions.Permission.WRITE, link.resolve("new.txt").toString()));
        assertFalse(permissions.allows(RuntimePermissions.Permission.WRITE, escape.resolve("new.txt").toString()));
    }

    private static String escape(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String deepMessage(Throwable error) {
        StringBuilder message = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) message.append(current.getMessage()).append(' ');
            current = current.getCause();
        }
        return message.toString();
    }
}
