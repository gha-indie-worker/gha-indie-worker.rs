# Actor failure effects

Oreslang treats guest exceptional control as three separate effects:

| Effect | Local catcher | At an actor mailbox boundary |
| --- | --- | --- |
| `throw value;` | `catch (err)`, `trap` | Aborts only the current operation; actor survives |
| `raise value;` | `rt recover { ... } on raise (Error err) { ... }` | Unrecovered raise terminates the actor |
| `panic value;` | No guest catch | Always terminates the actor |

The recovery construct is an explicit *same-execution-domain* statement. The VM records the creating actor identity and rejects recovery in a different actor domain. It runs its
handler after unwinding, never at the interrupted program counter. A panic is not
a raise and cannot be recovered inside the failed actor. A supervisor must create a
replacement actor if the application wishes to restart after failure.

`try/catch` binds the guest `throw` payload; it does not absorb cancellation,
raise, panic, VM-fatal failure, security violations, or unexpected JVM runtime
exceptions. A `trap` callable converts an uncaught ordinary guest throw to
`None`; it must not suppress nonlocal or terminal effects.

Mailbox errors are observable on `ActorRef.messageFailureCount()` and
`ActorRef.lastMessageFailure()`. Only a message and immutable diagnostic string
escape the owning actor domain; the original guest object is never exported.
Request/reply operations reject their owning Future instead. The observer APIs
are snapshots, **not** a durable error stream or per-message acknowledgment.
Supervisor events and a full request-envelope correlation API remain future work.

A source throw is **not** a transaction: actor state changes made before the
throw remain unless the application explicitly rolls them back.

Native/JVM fatal errors can still compromise an in-process VM. Untrusted native
execution requires physical isolation plus a revocable resource boundary. This
contract provides guest-level fault isolation, not a promise to survive host OOM,
native memory corruption, or JVM process termination.
