package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;

/**
 * The nominal actor-pipeline type rule, shared with the native admission gate.
 *
 * This is a HOST/compiler contract, not runtime wiring and not permission to
 * pass an ordinary ActorRef to a pipe. The source compiler must lower the
 * checked stages and allocate/connect bounded mailboxes before permitting
 * `spawn A() pipe spawn B()` in executable source.
 */
public final class ActorPipelineContract {
    private ActorPipelineContract() { }

    public enum Role {
        READABLE, TRANSFORM, WRITABLE, DUPLEX, ORDINARY_ACTOR, SUPERVISOR_ACTOR
    }

    /** Types are canonical nominal payload type names, not Java host classes. */
    public record Stage(String actorType, Role role, String inboundType, String outboundType) {
        public Stage {
            if (actorType == null || actorType.isBlank())
                throw new IllegalArgumentException("pipeline actor type cannot be blank");
            Objects.requireNonNull(role, "role");
            if (inboundType != null && inboundType.isBlank())
                throw new IllegalArgumentException("pipeline inbound type cannot be blank");
            if (outboundType != null && outboundType.isBlank())
                throw new IllegalArgumentException("pipeline outbound type cannot be blank");
            switch (role) {
                case READABLE -> {
                    if (inboundType != null || outboundType == null)
                        throw new IllegalArgumentException("readable pipe actor must only emit");
                }
                case TRANSFORM, DUPLEX -> {
                    if (inboundType == null || outboundType == null)
                        throw new IllegalArgumentException(role + " pipe actor requires input and output");
                }
                case WRITABLE -> {
                    if (inboundType == null || outboundType != null)
                        throw new IllegalArgumentException("writable pipe actor must only receive");
                }
                case ORDINARY_ACTOR, SUPERVISOR_ACTOR -> {
                    // Reject at validate() with the explicit forbidden-role diagnostic.
                }
            }
        }
    }

    public record Connection(String fromActor, String toActor, String payloadType) {
        public Connection {
            Objects.requireNonNull(fromActor);
            Objects.requireNonNull(toActor);
            Objects.requireNonNull(payloadType);
        }
    }

    /** This enforces pipeline construction admission, not message delivery. */
    public static List<Connection> validate(List<Stage> stages) {
        Objects.requireNonNull(stages, "stages");
        if (stages.size() < 2)
            throw new IllegalArgumentException("actor pipeline requires at least two stages");
        for (int i = 0; i < stages.size(); i++) {
            Stage stage = Objects.requireNonNull(stages.get(i), "pipeline stage");
            if (stage.role() == Role.SUPERVISOR_ACTOR)
                throw new IllegalArgumentException("supervisor actors cannot be pipeline stages");
            if (stage.role() == Role.ORDINARY_ACTOR)
                throw new IllegalArgumentException(
                        "ordinary actor lacks explicit pipe role: " + stage.actorType());
            if (i == 0 && stage.role() != Role.READABLE && stage.role() != Role.DUPLEX)
                throw new IllegalArgumentException("actor pipeline source must be readable or duplex");
            if (i > 0 && i < stages.size() - 1
                    && stage.role() != Role.TRANSFORM && stage.role() != Role.DUPLEX)
                throw new IllegalArgumentException("actor pipeline middle stage must transform or duplex");
            if (i == stages.size() - 1
                    && stage.role() != Role.WRITABLE && stage.role() != Role.DUPLEX)
                throw new IllegalArgumentException("actor pipeline sink must be writable or duplex");
        }
        java.util.ArrayList<Connection> links = new java.util.ArrayList<>(stages.size() - 1);
        for (int i = 1; i < stages.size(); i++) {
            Stage source = stages.get(i - 1), target = stages.get(i);
            if (!Objects.equals(source.outboundType(), target.inboundType()))
                throw new IllegalArgumentException("actor pipe payload mismatch: "
                        + source.actorType() + " emits " + source.outboundType()
                        + " but " + target.actorType() + " requires " + target.inboundType());
            links.add(new Connection(source.actorType(), target.actorType(), source.outboundType()));
        }
        return List.copyOf(links);
    }
}
