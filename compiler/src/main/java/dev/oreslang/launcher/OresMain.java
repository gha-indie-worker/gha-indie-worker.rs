package dev.oreslang.launcher;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ReachabilityReport;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.NativeBuildMode;
import dev.oreslang.runtime.CorePerf;
import dev.oreslang.runtime.CoreDebug;
import dev.oreslang.runtime.CoreLog;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OresMain {
    private static final Pattern POSITIONED_DIAGNOSTIC = Pattern.compile(
            "^Oreslang\\s+(?:lexer|parse)\\s+error\\s+at\\s+(\\d+):(\\d+):\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    private OresMain() { }

    /**
     * Only the standalone command-line host is permitted to terminate its process.
     * Embedders use LinkedProgramRunner.runWithExitStatus() instead.
     */
    public static void main(String[] args) {
        int status = exitStatusForArgs(args);
        if (status != 0) System.exit(status);
    }

    /** Testable CLI boundary: syntax/configuration failures never masquerade as success. */
    static int exitStatusForArgs(String[] args) {
        try {
            int status = runForExitStatus(args);
            // PrintStream records failed writes instead of throwing IOException.
            // Flush and inspect both CLI streams before publishing success.
            if (status == 0 && (System.out.checkError() || System.err.checkError())) {
                System.err.println("oreslang: failed to publish CLI output");
                return 1;
            }
            return status;
        } catch (UsageException failure) {
            System.err.println("oreslang: " + failure.getMessage());
            return 2;
        } catch (Exception failure) {
            String message = failure.getMessage();
            System.err.println("oreslang: " +
                    ((message == null || message.isBlank())
                            ? failure.getClass().getSimpleName() : message));
            return 1;
        }
    }

    static String buildInfoJson() {
        return "{\"build_mode\":\"" + NativeBuildMode.kind().name().toLowerCase(Locale.ROOT)
                + "\",\"default_execution_mode\":\"" + NativeBuildMode.defaultExecutionMode()
                + "\",\"managed_reference_mode\":\""
                + NativeBuildMode.managedReferenceMode().name().toLowerCase(Locale.ROOT)
                + "\",\"guest_execution\":\"" + (NativeBuildMode.kind() == NativeBuildMode.Kind.AOT
                    ? "interpreter-no-jit" : "runtime-selected") + "\"}";
    }

    private static int runForExitStatus(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--build-info")) {
            System.out.println(buildInfoJson());
            return 0;
        }
        String perfEnv = System.getenv("ORES_CORE_PERF");
        boolean corePerf = "true".equalsIgnoreCase(perfEnv) || "1".equals(perfEnv);
        String coreLogLevel = System.getenv().getOrDefault("ORES_CORE_LOG_LEVEL", "off");
        String debugEnv = System.getenv("ORES_CORE_DEBUG");
        boolean coreDebug = "true".equalsIgnoreCase(debugEnv) || "1".equals(debugEnv);
        boolean strict = false;
        boolean checkOnly = false;
        boolean buildAnalysis = false;
        String reachabilityReportPath = null;
        List<String> buildDefines = new ArrayList<>();
        Set<String> buildEntryPoints = new LinkedHashSet<>();
        String mode = NativeBuildMode.defaultExecutionMode();
        String platform = "server";
        PermissionCheckMode permissionCheckMode = PermissionCheckMode.RUNTIME;
        RuntimePermissions.Builder permissionBuilder = new RuntimePermissions.Builder();
        EnumSet<RuntimePermissions.Permission> touchedPermissions =
                EnumSet.noneOf(RuntimePermissions.Permission.class);
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        Set<String> allowedHostClasses = new LinkedHashSet<>();
        String filename = null;

        for (String arg : args) {
            try {
                if (arg.equals("--strict-isolate")) strict = true;
                else if (arg.equals("--check")) checkOnly = true;
                else if (arg.equals("--core-perf")) corePerf = true;
                else if (arg.equals("--no-core-perf")) corePerf = false;
                else if (arg.startsWith("--core-log-level="))
                    coreLogLevel = arg.substring("--core-log-level=".length());
                else if (arg.equals("--no-core-log")) coreLogLevel = "off";
                else if (arg.equals("--core-debug")) coreDebug = true;
                else if (arg.equals("--no-core-debug")) coreDebug = false;
                else if (arg.equals("--build-analysis")) buildAnalysis = true;
                else if (arg.startsWith("--reachability-report=")) {
                    if (reachabilityReportPath != null) {
                        throw new UsageException("--reachability-report may be supplied only once");
                    }
                    reachabilityReportPath = arg.substring("--reachability-report=".length()).trim();
                    if (reachabilityReportPath.isEmpty()) {
                        throw new UsageException("--reachability-report requires a file path");
                    }
                }
                else if (arg.equals("--no-prompt")) {
                    // Oreslang permissions never prompt. This Deno-compatible
                    // spelling is accepted so automation can state that intent.
                } else if (arg.startsWith("--permission-check=")) {
                    permissionCheckMode = PermissionCheckMode.parse(
                            arg.substring("--permission-check=".length()));
                } else if (arg.equals("--allow-all")) {
                    for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
                        permissionBuilder.allowAll(permission);
                        touchedPermissions.add(permission);
                    }
                } else if (parsePermissionFlag(arg, permissionBuilder, touchedPermissions)) {
                    // handled by the scoped Deno-style permission parser
                } else if (arg.startsWith("--define=")) {
                    String raw = arg.substring("--define=".length()).trim();
                    if (raw.isEmpty()) throw new IllegalArgumentException("--define requires name=value");
                    buildDefines.add(raw);
                } else if (arg.startsWith("--entry=")) {
                    String raw = arg.substring("--entry=".length()).trim();
                    if (raw.isEmpty()) throw new IllegalArgumentException("--entry requires a symbol name");
                    buildEntryPoints.add(raw);
                } else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
                else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
                else if (arg.startsWith("--allow=")) {
                    String raw = arg.substring("--allow=".length());
                    if (raw.isBlank()) {
                        throw new UsageException("--allow requires a comma-separated capability list");
                    }
                    for (String value : raw.split(",", -1)) {
                        if (value.isBlank()) {
                            throw new UsageException("--allow cannot contain empty capabilities");
                        }
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(
                                value.trim().toUpperCase(Locale.ROOT)));
                    }
                } else if (arg.startsWith("--allow-host-class=")) {
                    String raw = arg.substring("--allow-host-class=".length()).trim();
                    if (raw.isEmpty()) {
                        throw new IllegalArgumentException("--allow-host-class requires a fully qualified Java class name");
                    }
                    allowedHostClasses.add(raw);
                } else if (arg.startsWith("--")) {
                    throw new UsageException("unknown option: " + arg);
                } else if (filename == null) filename = arg;
                else throw new UsageException("only one .ores or .java source file may be supplied");
            } catch (IllegalArgumentException invalidOption) {
                throw new UsageException(invalidOption.getMessage());
            }
        }

        // Report mutually exclusive CLI flags before opening/discovering a file.
        // An invalid invocation is status 2 even when the source path is absent.
        if (checkOnly && buildAnalysis) {
            throw new UsageException("--check and --build-analysis are mutually exclusive");
        }
        if (reachabilityReportPath != null && !buildAnalysis) {
            throw new UsageException("--reachability-report requires --build-analysis");
        }

        final CoreLog.Level admittedLogLevel;
        try {
            admittedLogLevel = CoreLog.Level.parse(coreLogLevel);
        } catch (IllegalArgumentException badLogLevel) {
            throw new UsageException(badLogLevel.getMessage());
        }
        CoreLog.configure(admittedLogLevel);
        CorePerf.configure(corePerf);
        CoreDebug.configure(coreDebug);
        try {
            NativeBuildMode.requireCompatible(ExecutionProfile.parse(mode, platform));
        } catch (IllegalArgumentException invalidOption) {
            throw new UsageException(invalidOption.getMessage());
        }
        Path path;
        if (filename == null) {
            OresProjectConfig project = OresProjectConfig.discover(
                    Path.of("").toAbsolutePath().normalize(),
                    System.getenv());
            path = project.mainEntrypoint().orElse(null);
            if (path == null) {
                printUsage();
                System.err.println("or define [entrypoints].main in " + OresProjectConfig.MANIFEST_NAME);
                return 2;
            }
            filename = path.toString();
        } else {
            try {
                path = Path.of(filename);
            } catch (java.nio.file.InvalidPathException invalidPath) {
                throw new UsageException("invalid source path: " + invalidPath.getMessage());
            }
        }
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("not a file: " + path);

        String directOresSource = filename.endsWith(".ores") ? readBoundedOresSource(path) : null;
        if (directOresSource != null) {
            formatSyntaxWarnings(path, directOresSource).forEach(System.err::println);
            formatSelectReturnWarnings(path, directOresSource).forEach(System.err::println);
        }

        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        final RuntimePermissions permissions;
        if (touchedPermissions.isEmpty()) {
            permissions = RuntimePermissions.fromCapabilities(policy.capabilities());
        } else {
            permissions = permissionBuilder.build();

            // A Deno-style permission flag is the authority for its category.
            // Remove any inherited/generic coarse grant, then restore it only
            // when the scoped policy grants at least one resource.
            for (RuntimePermissions.Permission permission : touchedPermissions) {
                policy = policy.withoutCapabilities(permission.capability());
            }
            Set<IsolatePolicy.Capability> scopedCapabilities = permissions.grantedCapabilities();
            if (!scopedCapabilities.isEmpty()) {
                policy = policy.withCapabilities(
                        scopedCapabilities.toArray(IsolatePolicy.Capability[]::new));
            }
        }

        if (buildAnalysis) {
            if (!filename.endsWith(".ores")) {
                throw new UsageException("--build-analysis currently requires a .ores source file");
            }
            String source = directOresSource;
            if (permissionCheckMode == PermissionCheckMode.COMPILE) {
                OresCompiler.validateForIsolate(source, policy);
            }
            Map<String, String> defines = BuildOptions.mergeDefines(System.getenv(), buildDefines);
            Set<String> entries = buildEntryPoints.isEmpty() ? Set.of("main") : Set.copyOf(buildEntryPoints);
            BuildOptions buildOptions = new BuildOptions(defines, entries, false);
            TreeShaker.Result result = OresCompiler.compileForBuild(
                    directOresSource,
                    buildOptions);

            if (reachabilityReportPath != null) {
                final Path reportPath;
                try {
                    reportPath = Path.of(reachabilityReportPath);
                } catch (java.nio.file.InvalidPathException invalidPath) {
                    throw new UsageException(
                            "invalid reachability report path: " + invalidPath.getMessage());
                }
                String report = ReachabilityReport.render(
                        result,
                        buildOptions,
                        "graal",
                        "analysis",
                        "graal-" + platform,
                        "executable");
                Files.writeString(reportPath, report + System.lineSeparator());
            }

            System.out.println("tree-shake retained:");
            result.retainedSymbols().stream().sorted().forEach(symbol -> System.out.println("  + " + symbol));
            System.out.println("tree-shake removed:");
            result.removedSymbols().stream().sorted().forEach(symbol -> System.out.println("  - " + symbol));
            return 0;
        }

        if (!buildDefines.isEmpty() || !buildEntryPoints.isEmpty() || reachabilityReportPath != null) {
            throw new UsageException("--define/--entry/--reachability-report require --build-analysis until the artifact build command is wired");
        }

        if (checkOnly) {
            try {
                LinkedProgramRunner.validate(
                        path,
                        System.getenv(),
                        policy,
                        permissionCheckMode);
            } catch (Exception error) {
                System.err.println(formatCheckDiagnostic(path, error));
                return 1;
            }
            return 0;
        }

        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        int exitStatus = LinkedProgramRunner.runWithExitStatus(
                path,
                policy,
                permissions,
                permissionCheckMode,
                profile,
                allowedHostClasses,
                System.getenv(),
                System.out,
                System.err).exitStatus();
        return exitStatus;
    }

    // Share admission policy with the linked-unit loader, including imports.
    private static String readBoundedOresSource(Path path) throws IOException {
        return dev.oreslang.runtime.BoundedSourceReader.readUtf8(path);
    }

    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) { super(message); }
    }

    private static boolean parsePermissionFlag(
            String arg,
            RuntimePermissions.Builder builder,
            EnumSet<RuntimePermissions.Permission> touched) {
        for (RuntimePermissions.Permission permission : RuntimePermissions.Permission.values()) {
            String allow = "--allow-" + permission.cliName();
            String deny = "--deny-" + permission.cliName();

            if (arg.equals(allow)) {
                builder.allowAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(allow + "=")) {
                String scopes = arg.substring((allow + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.allowCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
            if (arg.equals(deny)) {
                builder.denyAll(permission);
                touched.add(permission);
                return true;
            }
            if (arg.startsWith(deny + "=")) {
                String scopes = arg.substring((deny + "=").length());
                requirePermissionScopes(arg, scopes);
                builder.denyCsv(permission, scopes);
                touched.add(permission);
                return true;
            }
        }
        return false;
    }

    private static void requirePermissionScopes(String option, String scopes) {
        if (scopes.isBlank()) {
            throw new IllegalArgumentException(option.substring(0, option.indexOf('='))
                    + " requires a non-empty comma-separated scope or the bare flag for all");
        }
    }

    private static void printUsage() {
        System.err.println(
                "usage: oreslang-compiler [--check|--build-analysis] "
                        + "[--permission-check=runtime|compile] [--allow-all] "
                        + "[--allow-read[=PATH,...]] [--deny-read[=PATH,...]] "
                        + "[--allow-write[=PATH,...]] [--deny-write[=PATH,...]] "
                        + "[--allow-net[=HOST[:PORT],...]] [--deny-net[=HOST[:PORT],...]] "
                        + "[--allow-env[=NAME,...]] [--deny-env[=NAME,...]] "
                        + "[--allow-run[=CMD,...]] [--deny-run[=CMD,...]] "
                        + "[--allow-sys[=API,...]] [--deny-sys[=API,...]] "
                        + "[--allow-ffi[=PATH,...]] [--deny-ffi[=PATH,...]] "
                        + "[--allow-import[=PATH,...]] [--deny-import[=PATH,...]] "
                        + "[--no-prompt] [--define=name=value ...] [--entry=symbol ...] [--reachability-report=FILE] "
                        + "[--strict-isolate] [--core-perf|--no-core-perf] [--core-debug|--no-core-debug] "
                        + "[--core-log-level=off|error|warn|info|debug|trace] [--no-core-log] [--mode=aot|jit|hybrid] "
                        + "[--platform=server|windows|macos|linux|android|ios] "
                        + "[--allow=CAP,...] [--allow-host-class=java.util.ArrayList ...] "
                        + "[file.ores|file.java]");
    }

    static List<String> formatSyntaxWarnings(Path path, String source) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return Parser.parseWithWarnings(source).warnings().stream()
                    .map(warning -> normalized
                            + ":" + warning.line()
                            + ":" + warning.column()
                            + ": warning: " + warning.message())
                    .toList();
        } catch (IllegalArgumentException ignored) {
            // The normal compile/check path will report the authoritative parse
            // error. Do not replace it with a secondary warning-scan failure.
            return List.of();
        }
    }

    /**
     * Report no-result select arm returns on the normal CLI path, not only
     * through the programmatic diagnostics API. The linked program compiler
     * remains the authoritative checker; a failed preliminary analysis must
     * not mask imports, source errors, or the normal diagnostic location.
     */
    static List<String> formatSelectReturnWarnings(Path path, String source) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().stream()
                    .map(warning -> normalized + ": warning: " + warning)
                    .toList();
        } catch (IllegalArgumentException ignored) {
            // Linked modules may need the import graph before full typechecking;
            // always let the authoritative compiler report the actual error.
            return List.of();
        }
    }

    static String formatCheckDiagnostic(Path path, Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();

        Matcher matcher = POSITIONED_DIAGNOSTIC.matcher(message);
        if (matcher.matches()) {
            return path.toAbsolutePath().normalize()
                    + ":" + matcher.group(1)
                    + ":" + matcher.group(2)
                    + ": error: " + matcher.group(3);
        }

        return path.toAbsolutePath().normalize() + ":1:1: error: " + message;
    }
}
