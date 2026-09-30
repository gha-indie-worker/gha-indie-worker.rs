#![allow(clippy::needless_return)]

use std::collections::BTreeSet;

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct State {
    desired: u16,
    admitted: u16,
    running: u16,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Action {
    Submit,
    Admit,
    Launch,
    StaleLaunch,
}

fn step(state: State, action: Action) -> State {
    return match action {
        Action::Submit => State {
            desired: state.desired + 1,
            ..state
        },
        Action::Admit => State {
            admitted: state.desired,
            ..state
        },
        Action::Launch => State {
            running: state.admitted,
            ..state
        },
        Action::StaleLaunch => state,
    };
}

fn assert_ordering(state: State) {
    assert!(
        state.running <= state.admitted && state.admitted <= state.desired,
        "deployment generation ordering violated: {state:?}"
    );
}

fn main() {
    let actions = [
        Action::Submit,
        Action::Admit,
        Action::Launch,
        Action::StaleLaunch,
    ];
    let initial = State {
        desired: 0,
        admitted: 0,
        running: 0,
    };
    let mut frontier = vec![initial];
    let mut seen = BTreeSet::from([initial]);

    for _depth in 0..9 {
        let mut next = Vec::new();

        for state in frontier {
            assert_ordering(state);

            for action in actions {
                let after = step(state, action);
                assert!(
                    after.desired >= state.desired,
                    "desired generation regressed: {state:?} -> {after:?}"
                );
                assert!(
                    after.admitted >= state.admitted,
                    "admitted generation regressed: {state:?} -> {after:?}"
                );
                assert!(
                    after.running >= state.running,
                    "running generation regressed: {state:?} -> {after:?}"
                );
                assert_ordering(after);

                if action == Action::StaleLaunch {
                    assert_eq!(after, state, "stale launch must be rejected as a no-op");
                }

                if seen.insert(after) {
                    next.push(after);
                }
            }
        }

        frontier = next;
    }

    assert_eq!(
        seen.len(),
        109,
        "bounded state-space size changed; inspect transition semantics"
    );
    println!(
        "formal deployment model: {} reachable states through depth 9; ok",
        seen.len()
    );
}
