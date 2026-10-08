package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScopedPermissionInteropTest {
    @Test
    void rawJdkFilesystemClassCannotBypassOresReadWriteScopes() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        Set.of("java.nio.file.Files")));

        assertTrue(denied.getMessage().contains("direct Java host I/O"));
    }

    @Test
    void rawJdkNetworkClassCannotBypassOresNetScopes() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP,
                IsolatePolicy.Capability.NETWORK);

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> policy.restrictedContextBuilder(
                        ExecutionProfile.serverJit(),
                        Set.of("java.net.Socket")));

        assertTrue(denied.getMessage().contains("direct Java host I/O"));
    }

    @Test
    void ordinaryNonIoHostClassCanStillUseExplicitInteropAllowlist() {
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.JAVA_INTEROP);

        assertDoesNotThrow(() -> policy.restrictedContextBuilder(
                ExecutionProfile.serverJit(),
                Set.of("java.util.ArrayList")));
    }
}
