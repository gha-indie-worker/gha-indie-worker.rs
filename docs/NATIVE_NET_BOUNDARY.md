# Oreslang native networking boundary

The networking architecture is:

```
Oreslang net/http stdlib (.ores)
        |
privileged native_net builtin ABI
        |
thin Java JNI declaration/binding layer (hosted Truffle build only)
        |
liboresnet / OS networking primitives
```

## Ownership

Oreslang owns protocol and API semantics: URI handling, HTTP serialization,
status/header parsing, framing, chunk decoding, redirects, authentication
policy, connection-pool policy, and public Java-shaped compatibility APIs.

Native code owns operating-system primitives: DNS, socket creation,
connect/bind/listen/accept, read/write, poll, socket options, and eventually
TLS primitives.

Java may marshal Truffle guest values into the privileged native ABI. Java must
not implement networking or HTTP semantics and must not delegate to
`java.net`, `java.net.http`, NIO sockets, or Java HTTP/TLS clients.

## Migration gate

The migration is complete only when:

1. `OresNet.java` contains no HTTP parser/serializer/redirect/URI engine.
2. Public `net` and `net.http` modules resolve to Oreslang stdlib modules.
3. The stdlib uses only the capability-gated `native_net` primitive namespace.
4. Native primitives are tested independently and Oreslang protocol behavior is
   tested through guest source.
5. strict/untrusted isolates receive NETWORK only through explicit capability
   admission; they never receive unrestricted FFI/NATIVE merely to use HTTP.
