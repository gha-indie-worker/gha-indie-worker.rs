package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeTestingFrameworkTest {
    @Test
    void nativeFrameworkRunsPassSkipAndPropertyCases() throws Exception {
        String output = run(Path.of("examples/native-testing.ores"));
        assertTrue(output.contains(
                "ORES_TEST|SUMMARY|total=4|passed=3|failed=0|skipped=1|filtered=0"));
    }

    @Test
    void nativeFrameworkEnforcesFilteringAndFailFast() throws Exception {
        String output = run(Path.of("examples/native-testing-semantics.ores"));
        assertTrue(output.contains(
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=2"));
        assertTrue(output.contains(
                "ORES_TEST|SUMMARY|total=2|passed=1|failed=1|skipped=0|filtered=0"));
        assertTrue(output.contains("ORES_TEST|FAIL|meta/failure|expected fail-fast sentinel"));
        assertFalse(output.contains("ORES_TEST|PASS|meta/third|\nORES_TEST|SUMMARY|total=2"));
    }

    private static String run(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}
