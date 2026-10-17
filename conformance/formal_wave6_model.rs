#![allow(clippy::needless_return)]

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Worker {
    A,
    B,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Phase {
    Read,
    Cas,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Event {
    worker: Worker,
    phase: Phase,
}

fn worker_index(worker: Worker) -> usize {
    return match worker {
        Worker::A => 0,
        Worker::B => 1,
    };
}

fn position(order: &[Event; 4], event: Event) -> usize {
    return order
        .iter()
        .position(|candidate| *candidate == event)
        .expect("every history must contain every event");
}

fn preserves_program_order(order: &[Event; 4]) -> bool {
    for worker in [Worker::A, Worker::B] {
        let read = Event {
            worker,
            phase: Phase::Read,
        };
        let cas = Event {
            worker,
            phase: Phase::Cas,
        };

        if position(order, read) >= position(order, cas) {
            return false;
        }
    }

    return true;
}

fn permute(events: &mut [Event; 4], index: usize, output: &mut Vec<[Event; 4]>) {
    if index == events.len() {
        output.push(*events);
        return;
    }

    for swap_index in index..events.len() {
        events.swap(index, swap_index);
        permute(events, index + 1, output);
        events.swap(index, swap_index);
    }
}

fn replay_claim(owner: Option<Worker>, worker: Worker) -> (Option<Worker>, bool) {
    return match owner {
        None => (Some(worker), true),
        Some(current) if current == worker => (Some(current), true),
        Some(current) => (Some(current), false),
    };
}

fn opposite(worker: Worker) -> Worker {
    return match worker {
        Worker::A => Worker::B,
        Worker::B => Worker::A,
    };
}

fn main() {
    let mut events = [
        Event {
            worker: Worker::A,
            phase: Phase::Read,
        },
        Event {
            worker: Worker::A,
            phase: Phase::Cas,
        },
        Event {
            worker: Worker::B,
            phase: Phase::Read,
        },
        Event {
            worker: Worker::B,
            phase: Phase::Cas,
        },
    ];
    let mut permutations = Vec::new();
    permute(&mut events, 0, &mut permutations);

    let mut histories = 0usize;
    let mut concurrent_histories = 0usize;

    for order in permutations {
        if !preserves_program_order(&order) {
            continue;
        }

        histories += 1;
        let mut owner = None;
        let mut observed: [Option<Option<Worker>>; 2] = [None, None];
        let mut success = [false, false];

        for event in order {
            let index = worker_index(event.worker);
            match event.phase {
                Phase::Read => {
                    observed[index] = Some(owner);
                }
                Phase::Cas => {
                    let expected =
                        observed[index].expect("program-order filtering requires read before CAS");
                    let won = owner == expected && expected.is_none();
                    if won {
                        owner = Some(event.worker);
                    }
                    success[index] = won;
                }
            }
        }

        let winner = match (success[0], success[1]) {
            (true, false) => Worker::A,
            (false, true) => Worker::B,
            _ => panic!("claim history produced zero or multiple winners: {success:?}"),
        };
        assert_eq!(
            owner,
            Some(winner),
            "final owner must be the successful CAS claimant"
        );

        for (first, second) in [(Worker::A, Worker::B), (Worker::B, Worker::A)] {
            let first_cas = Event {
                worker: first,
                phase: Phase::Cas,
            };
            let second_read = Event {
                worker: second,
                phase: Phase::Read,
            };
            if position(&order, first_cas) < position(&order, second_read) {
                assert_eq!(
                    winner, first,
                    "real-time precedence must fix linearization order"
                );
            }
        }

        let a_before_b = position(
            &order,
            Event {
                worker: Worker::A,
                phase: Phase::Cas,
            },
        ) < position(
            &order,
            Event {
                worker: Worker::B,
                phase: Phase::Read,
            },
        );
        let b_before_a = position(
            &order,
            Event {
                worker: Worker::B,
                phase: Phase::Cas,
            },
        ) < position(
            &order,
            Event {
                worker: Worker::A,
                phase: Phase::Read,
            },
        );
        if !a_before_b && !b_before_a {
            concurrent_histories += 1;
        }

        let (owner_after_replay, replay_ok) = replay_claim(owner, winner);
        assert!(replay_ok, "winner replay must be idempotently accepted");
        assert_eq!(
            owner_after_replay, owner,
            "winner replay must not change ownership"
        );

        let (owner_after_loser, loser_ok) = replay_claim(owner, opposite(winner));
        assert!(
            !loser_ok,
            "losing worker must not steal an established claim"
        );
        assert_eq!(
            owner_after_loser, owner,
            "loser replay must not change ownership"
        );
    }

    assert_eq!(
        histories, 6,
        "unexpected program-order-preserving history count"
    );
    assert!(
        concurrent_histories > 0,
        "no overlapping claim histories were explored"
    );
    println!(
        "worker claim interleaving linearizability model: {histories} histories, {concurrent_histories} concurrent; ok"
    );
}
