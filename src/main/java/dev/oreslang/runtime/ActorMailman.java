package dev.oreslang.runtime;

/**
 * Serialized ActorGroup outbox handler.
 *
 * One logical Mailman belongs to one configured ActorGroup. The runtime owns
 * scheduling and fairness; implementations handle one admitted mail at a time
 * and must not create their own endless scheduler loop.
 *
 * <p>This compatibility hook is for trusted, non-suspending host callbacks. An
 * arbitrary Java callback can block on host primitives the runtime cannot
 * cooperatively unwind. Mailmen that may wait must use
 * {@link CooperativeActorMailman} via
 * {@code ActorGroup.installCooperativeMailman(...)} so waits release CONTROL
 * carriers and resume through runtime scheduling.</p>
 */
@FunctionalInterface
public interface ActorMailman<Out> {
    void receiveMail(ActorMail<Out> mail, ActorGroupContext context) throws Exception;
}
