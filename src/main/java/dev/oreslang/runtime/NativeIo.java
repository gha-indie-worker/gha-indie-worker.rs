package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Trusted native host-I/O implementations for Oreslang.
 *
 * <p>Every operation checks permissions immediately before touching the host.
 * Guest code never receives Java IO/HTTP/socket objects.</p>
 */
public final class NativeIo {
    private NativeIo() { }

    public static String readText(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.read_text");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw ioFailure("read", path, failure);
        }
    }

    public static boolean exists(OresContext context, String rawPath) {
        Path path = canonicalIfExisting(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.exists");
        return Files.exists(path);
    }

    /**
     * Bounded, sorted immediate child names. This is deliberately NOT a path
     * walker: the guest checks each next component with is_symlink and is_dir.
     */
    public static List<String> listDir(OresContext context, String rawPath) {
        Path lexical = Path.of(rawPath).toAbsolutePath().normalize();
        context.requirePermission(RuntimePermissions.Permission.READ, lexical.toString(), "fs.list_dir");
        if (Files.isSymbolicLink(lexical)) throw new SecurityException("fs.list_dir refuses symlink: " + lexical);
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.READ, path.toString(), "fs.list_dir");
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("fs.list_dir requires directory: " + path);
        }
        try (java.util.stream.Stream<Path> stream = Files.list(path)) {
            List<String> names = stream.limit(20001).map(p -> p.getFileName().toString()).sorted().toList();
            if (names.size() > 20000) throw new IllegalArgumentException("fs.list_dir exceeds 20000 entries: " + path);
            return new java.util.ArrayList<>(names);
        } catch (IOException failure) {
            throw ioFailure("list directory", path, failure);
        }
    }

    /**
     * Resolve the parent directory before probing an entry. Permission checks
     * cover both the lexical path and its actual parent; a symlinked ancestor
     * cannot be used to inspect names outside the granted source root.
     * Metadata cannot provide race-free traversal on an attacker-writable tree.
     */
    private static Path metadataTarget(OresContext context, String rawPath, String operation) {
        Path lexical = lexicalPath(rawPath);
        Path parent = lexical.getParent();
        if (parent == null) throw new IllegalArgumentException("metadata path has no parent");
        // Metadata only reads the entry in its parent, not the target of a
        // symbolic link. Requiring READ on the child would canonicalize a
        // symlink to its target and incorrectly reject detecting unsafe links.
        // Resolve/check the parent instead; ancestor escapes still fail.
        Path canonicalParent = realPath(parent);
        Path target = canonicalParent.resolve(lexical.getFileName());
        try {
            context.requirePermission(RuntimePermissions.Permission.READ,
                    canonicalParent.toString(), operation);
        } catch (SecurityException parentDenied) {
            // A caller may have an exact READ grant for the root directory,
            // without a grant on /tmp or the parent of that root. Allow
            // metadata on that exact ordinary entry but never dereference an
            // ungranted terminal symlink.
            if (Files.isSymbolicLink(target)) throw parentDenied;
            context.requirePermission(RuntimePermissions.Permission.READ,
                    target.toString(), operation);
        }
        return target;
    }

    public static boolean isSymlink(OresContext context, String rawPath) {
        return Files.isSymbolicLink(metadataTarget(context, rawPath, "fs.is_symlink"));
    }

    public static boolean isDir(OresContext context, String rawPath) {
        return Files.isDirectory(metadataTarget(context, rawPath, "fs.is_dir"),
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Generate a complete UTF-8 file beside the destination and replace it
     * atomically. Never open the destination for truncation, nor follow a
     * symlink in the destination slot. The parent is canonicalized and checked
     * against the caller's WRITE grant before any mutation.
     */
    public static void writeTextAtomic(OresContext context, String rawPath, String value) {
        Path lexical = lexicalPath(rawPath);
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException("fs.write_text_atomic refuses symlink: " + lexical);
        }
        Path parent = lexical.getParent();
        if (parent == null) throw new IllegalArgumentException("atomic write path has no parent");
        Path canonicalParent = realPath(parent);
        Path target = canonicalParent.resolve(lexical.getFileName());
        context.requirePermission(RuntimePermissions.Permission.WRITE, lexical.toString(), "fs.write_text_atomic");
        context.requirePermission(RuntimePermissions.Permission.WRITE, target.toString(), "fs.write_text_atomic");
        if (!Files.isDirectory(canonicalParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("atomic write parent is not a directory: " + parent);
        }
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("fs.write_text_atomic refuses destination symlink: " + target);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(canonicalParent, ".oreslang-stack-", ".tmp");
            Files.writeString(temp, value, StandardCharsets.UTF_8);
            if (Files.isSymbolicLink(target)) {
                throw new SecurityException("fs.write_text_atomic refuses destination symlink: " + target);
            }
            Files.move(temp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw ioFailure("atomic write", target, failure);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    public static void writeText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.write_text");
        try {
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw ioFailure("write", path, failure);
        }
    }

    public static void appendText(OresContext context, String rawPath, String value) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.append_text");
        try {
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw ioFailure("append", path, failure);
        }
    }

    public static void createDirectories(OresContext context, String rawPath) {
        Path path = canonicalWritePath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.mkdir_all");
        try {
            Files.createDirectories(path);
        } catch (IOException failure) {
            throw ioFailure("create directory", path, failure);
        }
    }

    public static void remove(OresContext context, String rawPath) {
        Path path = canonicalExistingPath(rawPath);
        context.requirePermission(RuntimePermissions.Permission.WRITE, path.toString(), "fs.remove");
        try {
            Files.delete(path);
        } catch (IOException failure) {
            throw ioFailure("remove", path, failure);
        }
    }

    public static String envGet(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.get");
        return System.getenv(name);
    }

    public static boolean envHas(OresContext context, String name) {
        validateEnvName(name);
        context.requirePermission(RuntimePermissions.Permission.ENV, name, "env.has");
        return System.getenv().containsKey(name);
    }

    public static String httpGetText(OresContext context, String rawUri) {
        return httpText(context, "GET", rawUri, null);
    }

    public static String httpPostText(OresContext context, String rawUri, String body) {
        return httpText(context, "POST", rawUri, body);
    }

    private static String httpText(
            OresContext context,
            String method,
            String rawUri,
            String body) {
        URI uri = checkedHttpUri(rawUri);
        String endpoint = networkEndpoint(uri);
        context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "http." + method.toLowerCase());

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30));
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }

        // Redirects are deliberately disabled: silently following a redirect
        // to a second host would bypass the permission check above.
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try (client) {
            HttpResponse<String> response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        } catch (IOException failure) {
            throw new IllegalStateException("HTTP request failed for " + endpoint + ": " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted for " + endpoint, interrupted);
        }
    }

    public static TcpConnection connect(OresContext context, String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("network.connect host cannot be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("network.connect port must be 1..65535");
        }
        String endpoint = host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
        context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.connect");
        Socket socket = new Socket();
        try {
            context.nativeIo().register(socket);
            socket.connect(new InetSocketAddress(host, port), 15_000);
            return new TcpConnection(context, endpoint, socket);
        } catch (Exception failure) {
            context.nativeIo().unregister(socket);
            try { socket.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("network connection failed for " + endpoint + ": " + failure.getMessage(), failure);
        }
    }

    public static final class TcpConnection implements AutoCloseable {
        private final OresContext context;
        private final String endpoint;
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private TcpConnection(OresContext context, String endpoint, Socket socket) throws IOException {
            this.context = context;
            this.endpoint = endpoint;
            this.socket = socket;
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        public String readLine() {
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.socket.read_line");
            try {
                return reader.readLine();
            } catch (IOException failure) {
                throw new IllegalStateException("network read failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }

        public void writeText(String value) {
            context.requirePermission(RuntimePermissions.Permission.NET, endpoint, "network.socket.write_text");
            try {
                writer.write(value);
                writer.flush();
            } catch (IOException failure) {
                throw new IllegalStateException("network write failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
                context.nativeIo().unregister(socket);
            } catch (IOException failure) {
                throw new IllegalStateException("network close failed for " + endpoint + ": " + failure.getMessage(), failure);
            }
        }
    }

    private static URI checkedHttpUri(String rawUri) {
        final URI uri;
        try {
            uri = URI.create(rawUri);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid HTTP URL: " + rawUri, invalid);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("http API only accepts http:// or https:// URLs");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("HTTP URL must contain a host");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("HTTP URLs with embedded credentials are not accepted");
        }
        return uri;
    }

    private static String networkEndpoint(URI uri) {
        int port = uri.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        return uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    private static Path canonicalIfExisting(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        if (!Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return lexical;
        return realPath(lexical);
    }

    private static Path canonicalExistingPath(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        return realPath(lexical);
    }

    private static Path canonicalWritePath(String rawPath) {
        Path lexical = lexicalPath(rawPath);
        if (Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) return realPath(lexical);

        ArrayDeque<String> tail = new ArrayDeque<>();
        Path cursor = lexical;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) tail.addFirst(name.toString());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalArgumentException("cannot resolve filesystem permission path: " + rawPath);
        }

        Path resolved = realPath(cursor);
        for (String segment : tail) resolved = resolved.resolve(segment);
        return resolved.normalize();
    }

    private static Path lexicalPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("filesystem path cannot be blank");
        }
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            throw ioFailure("resolve", path, failure);
        }
    }

    private static void validateEnvName(String name) {
        if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("environment variable name is invalid");
        }
    }

    private static IllegalStateException ioFailure(String operation, Path path, IOException failure) {
        return new IllegalStateException(
                "filesystem " + operation + " failed for " + path + ": " + failure.getMessage(),
                failure);
    }
}
