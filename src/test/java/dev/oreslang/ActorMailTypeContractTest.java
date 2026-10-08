package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorMailTypeContractTest {

    @Test
    void typedReceiveDefinesActorRefSendPayloadAndEnvelopeMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val String value = mail.value;
                    val int sequence = mail.sequence;
                    val ActorId recipient = mail.recipient;
                    val Option<ActorId> sender = mail.sender;
                    stdio.println(value);
                    stdio.println(sequence);
                    stdio.println(recipient);
                    stdio.println(sender);
                    return;
                  }
                end

                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("hello");
                  return;
                }
                """)));
    }

    @Test
    void actorRefSendMustMatchReceivePayload() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(42);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("ActorRef.send mailbox value"),
                failure.getMessage());
    }

    @Test
    void actorWithoutReceiveCannotAcceptMailboxSend() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void { return; }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send("hello");
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("no receive(ActorMail<T>)"),
                failure.getMessage());
    }

    @Test
    void receiveIsActorLocalAndHasCanonicalShape() {
        IllegalArgumentException publicHandler = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub receive(ActorMail<String> mail): void { return; }
                        end
                        """)));
        assertTrue(publicHandler.getMessage().contains("actor-local"),
                publicHandler.getMessage());

        IllegalArgumentException wrongParameter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(String mail): void { return; }
                        end
                        """)));
        assertTrue(wrongParameter.getMessage().contains("ActorMail<T>"),
                wrongParameter.getMessage());

        IllegalArgumentException wrongReturn = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): int { return 1; }
                        end
                        """)));
        assertTrue(wrongReturn.getMessage().contains("must return void"),
                wrongReturn.getMessage());
    }

    @Test
    void actorMailRequiresExplicitSendablePayloadType() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail mail): void { return; }
                        end
                        """)));

        IllegalArgumentException closurePayload = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<Fnc<void>> mail): void { return; }
                        end
                        """)));
        assertTrue(closurePayload.getMessage().contains("sendable")
                        || closurePayload.getMessage().contains("actor-boundary"),
                closurePayload.getMessage());
    }

    @Test
    void receiveCannotBeInvokedLikeAnOrdinaryActorMethod() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.receive(mail);
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("reserved actor lifecycle hook"),
                failure.getMessage());
    }
}
