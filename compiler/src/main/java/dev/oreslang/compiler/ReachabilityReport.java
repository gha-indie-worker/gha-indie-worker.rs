package dev.oreslang.compiler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stable JSON projection of build reachability for cross-backend tooling.
 *
 * This intentionally depends only on the semantic TreeShaker result, not on
 * GraalVM internals. Final native-image byte measurements belong to the
 * artifact build stage and may be added by that producer.
 */
public final class ReachabilityReport {
    private static final Set<String> ARTIFACT_KINDS = Set.of(
            "executable", "library", "firmware", "wasm", "mobile-bundle",
            "fpga", "asic", "test", "other");

    private ReachabilityReport() { }

    public static String render(
            TreeShaker.Result result,
            BuildOptions options,
            String backend,
            String profile,
            String target,
            String artifactKind) {
        if (result == null || options == null) {
            throw new IllegalArgumentException("reachability report requires result and build options");
        }
        requireText(backend, "backend");
        requireText(profile, "profile");
        requireText(target, "target");
        if (!ARTIFACT_KINDS.contains(artifactKind)) {
            throw new IllegalArgumentException("unknown artifact kind: " + artifactKind);
        }

        List<String> retained = result.retainedSymbols().stream().sorted().toList();
        LinkedHashSet<String> roots = resolveRoots(result, options);

        StringBuilder out = new StringBuilder(512 + retained.size() * 96);
        out.append('{');
        field(out, "schema_version", "1.0.0").append(',');
        field(out, "backend", backend).append(',');
        field(out, "profile", profile).append(',');
        field(out, "target", target).append(',');
        field(out, "artifact_kind", artifactKind).append(',');

        out.append("\"roots\":[");
        String rootReason = options.preservePublicApi() ? "public library API" : "entry point";
        boolean first = true;
        for (String root : roots) {
            if (!first) out.append(',');
            first = false;
            node(out, root, rootReason);
        }
        out.append("],");

        out.append("\"retained\":[");
        first = true;
        for (String symbol : retained) {
            if (!first) out.append(',');
            first = false;
            node(out, symbol, roots.contains(symbol)
                    ? rootReason
                    : "transitively reachable from build root");
        }
        out.append("],");

        out.append("\"removed\":{\"total\":")
                .append(result.removedSymbols().size())
                .append("},");
        out.append("\"dynamic_regions\":[{");
        field(out, "id", "java-graal-semantic-dynamic-surface").append(',');
        field(out, "policy", "conservative").append(',');
        field(out, "reason",
                "Current TreeShaker does not yet expose per-dynamic-region provenance; unresolved module/class dynamic surfaces are conservatively retained");
        out.append("}],");
        out.append("\"notes\":[");
        string(out, "Java/Graal semantic reachability report; final artifact byte attribution is produced by the artifact build stage");
        out.append("]}");
        return out.toString();
    }

    private static LinkedHashSet<String> resolveRoots(
            TreeShaker.Result result, BuildOptions options) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        result.rootSymbols().stream().sorted().forEach(roots::add);
        return roots;
    }

    private static void node(StringBuilder out, String id, String reason) {
        out.append('{');
        field(out, "id", id).append(',');
        field(out, "kind", "other").append(',');
        field(out, "reason", reason);
        out.append('}');
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        string(out, name).append(':');
        return string(out, value);
    }

    private static StringBuilder string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"');
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
