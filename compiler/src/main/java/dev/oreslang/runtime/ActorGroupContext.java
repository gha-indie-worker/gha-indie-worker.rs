package dev.oreslang.runtime;

/**
 * Narrow capability surface exposed to an ActorGroup Mailman.
 *
 * CONTROL-carrier identity does not confer host/supervisor authority. Mailmen
 * may inspect their own group identity/member count and send through ActorRef
 * mailboxes; they do not receive ActorRuntime lifecycle/control capabilities.
 * Every operation requires an active CONTROL turn for the owning Mailman. For
 * synchronous Mailmen the context expires when receiveMail returns. A
 * CooperativeActorMailman may retain the context across suspension, but it is
 * inactive between resumptions and on Future/completion threads; only the next
 * runtime-scheduled CONTROL turn reactivates it. The capability must never be
 * forwarded outside that logical Mailman task.
 */
public interface ActorGroupContext {
    ActorRuntime.ActorGroupId groupId();

    int memberCount();

    <M> void send(ActorRuntime.ActorRef<M> target, M message);
}
