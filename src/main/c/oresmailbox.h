#ifndef ORES_MAILBOX_H
#define ORES_MAILBOX_H

#include <stddef.h>
#include <stdint.h>

/* Experimental Unix worker transport, not yet the source-actor backend.
 * Dedicated connected socketpair; one sender and one receiver per direction.
 * Transfer functions are blocking and deadline bounded. No guest pointers cross.
 */
#define ORES_MAILBOX_MAX_PAYLOAD 4096

enum ores_worker_trust { ORES_WORKER_TRUSTED = 1, ORES_WORKER_UNTRUSTED = 2 };

int ores_mailbox_pair(int descriptors[2]);
int ores_mailbox_send(int mailbox, const void *payload, size_t size, int timeout_ms);
int ores_mailbox_receive(int mailbox, void *payload, size_t capacity, size_t *size,
                         int timeout_ms);
/* Ownership precondition: *descriptor is the sole application-owned reference,
 * no dup aliases, pending I/O or concurrent users. A failed call may consume it;
 * -1 is authoritative. Success means queued, not delivered/handled.
 * Receiver cannot expose the descriptor until sender closes and commits.
 * No implicit retry after an ambiguous transfer failure; discard the mailbox.
 */
int ores_mailbox_move_fd(int mailbox, int *descriptor, uint64_t transfer_id,
                         enum ores_worker_trust trust, int timeout_ms);
int ores_mailbox_receive_fd(int mailbox, int *descriptor, uint64_t *transfer_id,
                            enum ores_worker_trust trust, int timeout_ms);
/* Hard binding of the calling carrier. Unsupported platforms fail with ENOTSUP. */
int ores_carrier_bind_cpu(unsigned int cpu);

#endif
