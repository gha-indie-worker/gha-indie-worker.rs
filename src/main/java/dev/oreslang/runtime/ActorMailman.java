package dev.oreslang.runtime;

/**
 * Serialized ActorGroup outbox handler.
 *
 * One logical Mailman belongs to one configured ActorGroup. The runtime owns
 * scheduling and fairness; implementations handle one admitted mail at a time
 * and must not create their own endless scheduler loop.
 */
@FunctionalInterface
public interface ActorMailman<Out> {
    void receiveMail(ActorMail<Out> mail, ActorGroupContext context) throws Exception;
}
