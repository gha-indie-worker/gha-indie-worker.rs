//! Transport-neutral, deny-by-default ProxyActor routing model.
//!
//! This crate does **not** perform network IO or implement source-language
//! ProxyActor. It models endpoint lease fencing, actor identity, ready/close
//! transitions, and per-stream credits, independently of IPC/TCP/HTTP2.
//! A transport adapter must authenticate peers and bind the observed identity
//! to the handshake before calling `confirm_handshake`.

use std::collections::BTreeMap;

/// Conservative per-connection ceilings. Transport adapters must also
/// enforce total memory accounting and serialized payload size limits.
pub const MAX_STREAMS_PER_PROXY: usize = 256;
pub const MAX_MESSAGES_PER_STREAM: u64 = 4096;
pub const MAX_WINDOW_BYTES_PER_STREAM: u64 = 1024 * 1024;
pub const MAX_ENDPOINT_BYTES: usize = 2048;

#[derive(Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct RemoteActorId {
    pub namespace: String,
    pub service: String,
    pub actor_id: u128,
    pub incarnation: u64,
    pub contract_hash: [u8; 32],
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Transport {
    Ipc,
    TcpTls,
    Http2Tls,
}

/// Independent local-to-remote and remote-to-local dataflow directions.
/// A reverse stream never shares its sequence or credit counter with a
/// forward stream carrying the same numeric ID.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub enum Direction {
    Forward,
    Reverse,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EndpointLease {
    pub actor: RemoteActorId,
    pub endpoint: String,
    pub authority: String,
    pub transport: Transport,
    /// Freshness is checked against a monotonic runtime clock, not wall time.
    pub expires_at_tick: u64,
    pub routing_generation: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AuthenticatedPeer {
    pub actor: RemoteActorId,
    pub authority: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RouteError {
    ExpiredLease,
    StaleGeneration,
    WrongService,
    WrongNamespace,
    WrongAuthority,
    ContractMismatch,
    ActorReplaced,
    TransportDenied,
    InvalidEndpoint,
    NotConnecting,
    AlreadyConnected,
    AuthenticationMismatch,
    NotReady,
    StaleSession,
    StaleStream,
    Closing,
    DuplicateStream,
    MissingStream,
    DuplicateOrStaleSequence,
    WindowExceeded,
    InvalidCredit,
    CreditOverflow,
    OversizeFrame,
    TooManyStreams,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum RouteState {
    Unresolved,
    Connecting { generation: u64 },
    Ready { generation: u64, session_epoch: u64 },
    Disconnected,
    Draining,
    Closed,
}

/// Session capability produced only after authenticated handshake. An IO
/// callback must retain this exact handle across asynchronous suspension.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SessionHandle { generation: u64, epoch: u64 }

/// Stream incarnation capability; stream IDs can be reused safely only when
/// callbacks retain the actual handle returned by open_stream.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct StreamHandle {
    session: SessionHandle,
    direction: Direction,
    id: u64,
    incarnation: u64,
}

/// The caller must restrict trusted service names/addresses independently;
/// routing strings must never be interpolated into OS commands or raw URLs.
pub struct ProxyRoute {
    namespace: String,
    service: String,
    authority: String,
    contract_hash: [u8; 32],
    transports: Vec<Transport>,
    state: RouteState,
    /// Once pinned, redirecting to another actor/incarnation is not automatic.
    pinned: Option<RemoteActorId>,
    selected: Option<EndpointLease>,
    highest_generation: Option<u64>,
    session_epoch: u64,
    next_stream_incarnation: u64,
    streams: BTreeMap<(Direction, u64), StreamWindow>,
}

impl ProxyRoute {
    pub fn new(
        namespace: impl Into<String>,
        service: impl Into<String>,
        authority: impl Into<String>,
        contract_hash: [u8; 32],
        transports: Vec<Transport>,
    ) -> Self {
        Self {
            namespace: namespace.into(),
            service: service.into(),
            authority: authority.into(),
            contract_hash,
            transports,
            state: RouteState::Unresolved,
            pinned: None,
            selected: None,
            highest_generation: None,
            session_epoch: 0,
            next_stream_incarnation: 0,
            streams: BTreeMap::new(),
        }
    }

    pub fn state(&self) -> &RouteState { &self.state }
    pub fn pinned(&self) -> Option<&RemoteActorId> { self.pinned.as_ref() }

    pub fn begin_connect(
        &mut self,
        lease: EndpointLease,
        now_tick: u64,
    ) -> Result<u64, RouteError> {
        if matches!(self.state, RouteState::Draining | RouteState::Closed) {
            return Err(RouteError::Closing);
        }
        if matches!(self.state, RouteState::Ready { .. } | RouteState::Connecting { .. }) {
            return Err(RouteError::AlreadyConnected);
        }
        if lease.expires_at_tick <= now_tick { return Err(RouteError::ExpiredLease); }
        if lease.actor.service != self.service { return Err(RouteError::WrongService); }
        if lease.actor.namespace != self.namespace { return Err(RouteError::WrongNamespace); }
        if lease.authority != self.authority { return Err(RouteError::WrongAuthority); }
        if lease.actor.contract_hash != self.contract_hash {
            return Err(RouteError::ContractMismatch);
        }
        if !self.transports.contains(&lease.transport) {
            return Err(RouteError::TransportDenied);
        }
        // Do not confuse a transport address with an authenticated actor ID.
        // Scheme checks prevent accidental plaintext downgrade; connectors must
        // still validate their own host/address policy and peer credentials.
        let remainder = match lease.transport {
            Transport::Ipc => lease.endpoint.strip_prefix("unix:///"),
            Transport::TcpTls => lease.endpoint.strip_prefix("tls://"),
            Transport::Http2Tls => lease.endpoint.strip_prefix("https://"),
        };
        let Some(remainder) = remainder else {
            return Err(RouteError::InvalidEndpoint);
        };
        if remainder.is_empty() || lease.endpoint.len() > MAX_ENDPOINT_BYTES
            || lease.endpoint.chars().any(|ch| ch.is_control() || ch.is_whitespace())
            || lease.endpoint.contains('@') || lease.endpoint.contains('#')
            || lease.endpoint.contains('?')
            || (lease.transport != Transport::Ipc && remainder.starts_with('/')) {
            return Err(RouteError::InvalidEndpoint);
        }
        if self.pinned.as_ref().is_some_and(|actor| actor != &lease.actor) {
            return Err(RouteError::ActorReplaced);
        }
        if self.highest_generation.is_some_and(|generation| lease.routing_generation <= generation) {
            return Err(RouteError::StaleGeneration);
        }
        let generation = lease.routing_generation;
        self.highest_generation = Some(generation);
        self.selected = Some(lease);
        self.state = RouteState::Connecting { generation };
        // Abandon all unacknowledged window state on session replacement.
        // A real adapter must fail pending Futures as outcome-unknown instead
        // of replaying them without an idempotency guarantee.
        self.streams.clear();
        Ok(generation)
    }

    pub fn confirm_handshake(
        &mut self,
        generation: u64,
        peer: AuthenticatedPeer,
    ) -> Result<SessionHandle, RouteError> {
        if self.state != (RouteState::Connecting { generation }) {
            return Err(RouteError::NotConnecting);
        }
        let selected = self.selected.as_ref().ok_or(RouteError::NotConnecting)?;
        if peer.authority != selected.authority || peer.actor != selected.actor {
            // Authenticated identity mismatch poisons this attempted session.
            // It cannot be retried in-place with a different claimed peer.
            self.selected = None;
            self.state = RouteState::Disconnected;
            self.streams.clear();
            return Err(RouteError::AuthenticationMismatch);
        }
        if self.pinned.as_ref().is_some_and(|old| old != &peer.actor) {
            return Err(RouteError::ActorReplaced);
        }
        self.pinned = Some(peer.actor);
        self.session_epoch = self.session_epoch.checked_add(1).ok_or(RouteError::CreditOverflow)?;
        self.state = RouteState::Ready { generation, session_epoch: self.session_epoch };
        Ok(SessionHandle { generation, epoch: self.session_epoch })
    }

    pub fn disconnect(&mut self, generation: u64) -> bool {
        let current = match self.state {
            RouteState::Connecting { generation: g } | RouteState::Ready { generation: g, .. } => g,
            _ => return false,
        };
        if current != generation { return false; }
        self.state = RouteState::Disconnected;
        self.streams.clear();
        true
    }

    pub fn start_drain(&mut self) {
        if self.state != RouteState::Closed {
            self.state = RouteState::Draining;
            self.streams.clear();
        }
    }

    pub fn close(&mut self) {
        self.state = RouteState::Closed;
        self.selected = None;
        self.streams.clear();
    }

    fn check_session(&self, session: SessionHandle) -> Result<(), RouteError> {
        match self.state {
            RouteState::Ready { generation, session_epoch }
                if session == (SessionHandle { generation, epoch: session_epoch }) => Ok(()),
            RouteState::Ready { .. } => Err(RouteError::StaleSession),
            _ => Err(RouteError::NotReady),
        }
    }

    pub fn open_stream(
        &mut self,
        session: SessionHandle,
        direction: Direction,
        id: u64,
        maximum_messages: u64,
        maximum_bytes: u64,
    ) -> Result<StreamHandle, RouteError> {
        self.check_session(session)?;
        if maximum_messages == 0 || maximum_bytes == 0
            || maximum_messages > MAX_MESSAGES_PER_STREAM
            || maximum_bytes > MAX_WINDOW_BYTES_PER_STREAM {
            return Err(RouteError::InvalidCredit);
        }
        if self.streams.contains_key(&(direction, id)) { return Err(RouteError::DuplicateStream); }
        if self.streams.len() >= MAX_STREAMS_PER_PROXY {
            return Err(RouteError::TooManyStreams);
        }
        let incarnation = self.next_stream_incarnation.checked_add(1)
            .ok_or(RouteError::CreditOverflow)?;
        self.next_stream_incarnation = incarnation;
        let handle = StreamHandle { session, direction, id, incarnation };
        self.streams.insert((direction, id), StreamWindow {
            handle,
            maximum_messages,
            maximum_bytes,
            available_messages: 0,
            available_bytes: 0,
            next_sequence: 0,
        });
        Ok(handle)
    }

    pub fn grant_credit(&mut self, stream: StreamHandle,
        messages: u64, bytes: u64) -> Result<(), RouteError> {
        self.check_session(stream.session)?;
        if messages == 0 && bytes == 0 { return Err(RouteError::InvalidCredit); }
        let w = self.streams.get_mut(&(stream.direction, stream.id))
            .ok_or(RouteError::MissingStream)?;
        if w.handle != stream { return Err(RouteError::StaleStream); }
        let next_messages = w.available_messages.checked_add(messages)
                .ok_or(RouteError::CreditOverflow)?;
        let next_bytes = w.available_bytes.checked_add(bytes)
                .ok_or(RouteError::CreditOverflow)?;
        if next_messages > w.maximum_messages || next_bytes > w.maximum_bytes {
            return Err(RouteError::WindowExceeded);
        }
        w.available_messages = next_messages;
        w.available_bytes = next_bytes;
        Ok(())
    }

    /// Admission consumes one message credit and the exact payload byte size.
    /// It never claims processing/durability acknowledgement.
    pub fn admit_data(&mut self, stream: StreamHandle,
        sequence: u64, payload_bytes: u64) -> Result<(), RouteError> {
        self.check_session(stream.session)?;
        let w = self.streams.get_mut(&(stream.direction, stream.id))
            .ok_or(RouteError::MissingStream)?;
        if w.handle != stream { return Err(RouteError::StaleStream); }
        if sequence != w.next_sequence { return Err(RouteError::DuplicateOrStaleSequence); }
        if payload_bytes > w.maximum_bytes { return Err(RouteError::OversizeFrame); }
        if w.available_messages == 0 || payload_bytes > w.available_bytes {
            return Err(RouteError::WindowExceeded);
        }
        let next = w.next_sequence.checked_add(1).ok_or(RouteError::CreditOverflow)?;
        w.available_messages -= 1;
        w.available_bytes -= payload_bytes;
        w.next_sequence = next;
        Ok(())
    }

    pub fn finish_stream(&mut self, stream: StreamHandle) -> Result<(), RouteError> {
        self.check_session(stream.session)?;
        let existing = self.streams.get(&(stream.direction, stream.id))
            .ok_or(RouteError::MissingStream)?;
        if existing.handle != stream { return Err(RouteError::StaleStream); }
        self.streams.remove(&(stream.direction, stream.id));
        Ok(())
    }
}

struct StreamWindow {
    handle: StreamHandle,
    maximum_messages: u64,
    maximum_bytes: u64,
    available_messages: u64,
    available_bytes: u64,
    next_sequence: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    // These convenience methods are test-only. Transport adapters must keep
    // the original callback handles and use the capability-bound public API.
    impl ProxyRoute {
        fn test_session(&self) -> SessionHandle {
            match self.state {
                RouteState::Ready { generation, session_epoch } =>
                    SessionHandle { generation, epoch: session_epoch },
                _ => SessionHandle { generation: 0, epoch: 0 },
            }
        }
        fn test_handle(&self, direction: Direction, id: u64) -> StreamHandle {
            self.streams.get(&(direction, id)).map(|w| w.handle).unwrap_or(
                StreamHandle { session: self.test_session(), direction, id, incarnation: 0 })
        }
        fn test_confirm_handshake(&mut self, generation: u64, peer: AuthenticatedPeer)
            -> Result<u64, RouteError> {
            self.confirm_handshake(generation, peer).map(|h| h.epoch)
        }
        fn test_open_stream(&mut self, direction: Direction, id: u64,
            messages: u64, bytes: u64) -> Result<(), RouteError> {
            let session = self.test_session();
            self.open_stream(session, direction, id, messages, bytes).map(|_| ())
        }
        fn test_grant_credit(&mut self, direction: Direction, id: u64,
            messages: u64, bytes: u64) -> Result<(), RouteError> {
            let handle = self.test_handle(direction, id);
            self.grant_credit(handle, messages, bytes)
        }
        fn test_admit_data(&mut self, direction: Direction, id: u64,
            sequence: u64, bytes: u64) -> Result<(), RouteError> {
            let handle = self.test_handle(direction, id);
            self.admit_data(handle, sequence, bytes)
        }
        fn test_finish_stream(&mut self, direction: Direction, id: u64)
            -> Result<(), RouteError> {
            let handle = self.test_handle(direction, id);
            self.finish_stream(handle)
        }
    }

    fn actor(incarnation: u64) -> RemoteActorId {
        RemoteActorId {
            namespace: "tenant-one".into(),
            service: "transform.v1".into(),
            actor_id: 123,
            incarnation,
            contract_hash: [7; 32],
        }
    }

    fn lease(incarnation: u64, generation: u64) -> EndpointLease {
        EndpointLease {
            actor: actor(incarnation),
            endpoint: "tls://worker.example:8443".into(),
            authority: "trusted-worker".into(),
            transport: Transport::TcpTls,
            expires_at_tick: 100,
            routing_generation: generation,
        }
    }

    fn route() -> ProxyRoute {
        ProxyRoute::new(
            "tenant-one", "transform.v1", "trusted-worker",
            [7; 32], vec![Transport::TcpTls],
        )
    }

    fn connected() -> ProxyRoute {
        let mut proxy = route();
        assert_eq!(proxy.begin_connect(lease(1, 1), 20), Ok(1));
        assert_eq!(proxy.test_confirm_handshake(1, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Ok(1));
        proxy
    }

    #[test]
    fn handshake_requires_authenticated_actor_identity() {
        let mut proxy = route();
        assert_eq!(proxy.begin_connect(lease(1, 10), 20), Ok(10));
        assert_eq!(proxy.test_confirm_handshake(10, AuthenticatedPeer {
            actor: actor(2), authority: "trusted-worker".into(),
        }), Err(RouteError::AuthenticationMismatch));
        assert_eq!(proxy.test_confirm_handshake(10, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Err(RouteError::NotConnecting));
        assert_eq!(proxy.begin_connect(lease(1, 11), 20), Ok(11));
        assert_eq!(proxy.test_confirm_handshake(10, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Err(RouteError::NotConnecting));
        assert_eq!(proxy.test_confirm_handshake(11, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Ok(1));
    }

    #[test]
    fn lease_is_fenced_against_expiry_tenant_auth_and_downgrade() {
        let mut p = route();
        assert_eq!(p.begin_connect(lease(1, 1), 100), Err(RouteError::ExpiredLease));
        let mut wrong = lease(1, 1);
        wrong.actor.namespace = "attacker".into();
        assert_eq!(p.begin_connect(wrong, 10), Err(RouteError::WrongNamespace));
        let mut wrong = lease(1, 1);
        wrong.authority = "attacker".into();
        assert_eq!(p.begin_connect(wrong, 10), Err(RouteError::WrongAuthority));
        let mut wrong = lease(1, 1);
        wrong.transport = Transport::Ipc;
        assert_eq!(p.begin_connect(wrong, 10), Err(RouteError::TransportDenied));
        let mut wrong = lease(1, 1);
        wrong.endpoint = "tcp://unencrypted:8080".into();
        assert_eq!(p.begin_connect(wrong, 10), Err(RouteError::InvalidEndpoint));
        for bad in ["tls://", "tls://user@host:443",
                    "tls://host:443\nmalicious", "tls://host:443#redirect"] {
            let mut wrong = lease(1, 1);
            wrong.endpoint = bad.into();
            assert_eq!(p.begin_connect(wrong, 10), Err(RouteError::InvalidEndpoint));
        }
    }

    #[test]
    fn reconnect_cannot_silently_change_remote_incarnation() {
        let mut p = connected();
        assert!(p.disconnect(1));
        assert!(!p.disconnect(1));
        assert_eq!(p.begin_connect(lease(2, 2), 21), Err(RouteError::ActorReplaced));
        assert_eq!(p.begin_connect(lease(1, 1), 21), Err(RouteError::StaleGeneration));
        assert_eq!(p.begin_connect(lease(1, 2), 21), Ok(2));
        assert_eq!(p.test_confirm_handshake(2, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Ok(2));
        assert!(!p.disconnect(1)); // old transport callback is fenced
        assert!(matches!(p.state(), RouteState::Ready { session_epoch: 2, .. }));
    }

    #[test]
    fn connected_session_cannot_be_superseded_without_explicit_disconnect() {
        let mut p = connected();
        assert_eq!(p.begin_connect(lease(1, 2), 21), Err(RouteError::AlreadyConnected));
        assert!(matches!(p.state(), RouteState::Ready { generation: 1, .. }));
        assert!(p.disconnect(1));
        assert_eq!(p.begin_connect(lease(1, 2), 21), Ok(2));
        assert_eq!(p.begin_connect(lease(1, 3), 21), Err(RouteError::AlreadyConnected));
    }

    #[test]
    fn bounded_window_preserves_sequence_and_rejects_overproduction() {
        let mut p = connected();
        assert_eq!(p.test_open_stream(Direction::Forward, 42, 2, 100), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 0, 1), Err(RouteError::WindowExceeded));
        assert_eq!(p.test_grant_credit(Direction::Forward, 42, 2, 60), Ok(()));
        assert_eq!(p.test_grant_credit(Direction::Forward, 42, 1, 50), Err(RouteError::WindowExceeded));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 0, 40), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 0, 20), Err(RouteError::DuplicateOrStaleSequence));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 1, 40), Err(RouteError::WindowExceeded));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 1, 20), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 2, 0), Err(RouteError::WindowExceeded));
        assert_eq!(p.test_grant_credit(Direction::Forward, 42, 1, 80), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 2, 101), Err(RouteError::OversizeFrame));
        assert_eq!(p.test_admit_data(Direction::Forward, 42, 2, 80), Ok(()));
        assert_eq!(p.test_finish_stream(Direction::Forward, 42), Ok(()));
        assert_eq!(p.test_finish_stream(Direction::Forward, 42), Err(RouteError::MissingStream));
    }

    #[test]
    fn forward_and_reverse_have_independent_windows_and_sequences() {
        let mut p = connected();
        assert_eq!(p.test_open_stream(Direction::Forward, 9, 1, 5), Ok(()));
        assert_eq!(p.test_open_stream(Direction::Reverse, 9, 1, 8), Ok(()));
        assert_eq!(p.test_open_stream(Direction::Forward, 9, 1, 5), Err(RouteError::DuplicateStream));
        assert_eq!(p.test_grant_credit(Direction::Forward, 9, 1, 5), Ok(()));
        assert_eq!(p.test_grant_credit(Direction::Reverse, 9, 1, 8), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 9, 0, 5), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Reverse, 9, 0, 8), Ok(()));
        assert_eq!(p.test_admit_data(Direction::Forward, 9, 1, 0), Err(RouteError::WindowExceeded));
        assert_eq!(p.test_finish_stream(Direction::Forward, 9), Ok(()));
        assert_eq!(p.test_finish_stream(Direction::Reverse, 9), Ok(()));
    }

    #[test]
    fn enforces_stream_and_credit_caps_before_admission() {
        let mut p = connected();
        assert_eq!(p.test_open_stream(Direction::Forward, 0,
            MAX_MESSAGES_PER_STREAM + 1, 1), Err(RouteError::InvalidCredit));
        assert_eq!(p.test_open_stream(Direction::Forward, 0,
            1, MAX_WINDOW_BYTES_PER_STREAM + 1), Err(RouteError::InvalidCredit));
        for id in 0..MAX_STREAMS_PER_PROXY as u64 {
            assert_eq!(p.test_open_stream(Direction::Forward, id, 1, 1), Ok(()));
        }
        assert_eq!(p.test_open_stream(Direction::Reverse, 0, 1, 1),
            Err(RouteError::TooManyStreams));
        assert_eq!(p.test_finish_stream(Direction::Forward, 0), Ok(()));
        assert_eq!(p.test_open_stream(Direction::Reverse, 0, 1, 1), Ok(()));
    }

    #[test]
    fn cancellation_blocks_late_handshakes_and_stream_resurrection() {
        let mut p = route();
        assert_eq!(p.begin_connect(lease(1, 4), 10), Ok(4));
        p.start_drain();
        assert_eq!(p.test_confirm_handshake(4, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }), Err(RouteError::NotConnecting));
        assert_eq!(p.begin_connect(lease(1, 5), 10), Err(RouteError::Closing));
        p.close();
        assert_eq!(p.test_open_stream(Direction::Forward, 1, 1, 100), Err(RouteError::NotReady));
    }
    #[test]
    fn callbacks_from_disconnected_session_cannot_mutate_reused_stream_id() {
        let mut p = connected();
        let old_session = p.test_session();
        let old_stream = p.open_stream(old_session, Direction::Forward, 42, 2, 32).unwrap();
        p.grant_credit(old_stream, 1, 32).unwrap();
        assert!(p.disconnect(1));
        assert_eq!(p.begin_connect(lease(1, 2), 20), Ok(2));
        let new_session = p.confirm_handshake(2, AuthenticatedPeer {
            actor: actor(1), authority: "trusted-worker".into(),
        }).unwrap();
        let new_stream = p.open_stream(new_session, Direction::Forward, 42, 2, 32).unwrap();
        assert_ne!(old_stream, new_stream);
        assert_eq!(p.open_stream(old_session, Direction::Reverse, 9, 1, 1),
                   Err(RouteError::StaleSession));
        assert_eq!(p.grant_credit(old_stream, 1, 32), Err(RouteError::StaleSession));
        assert_eq!(p.admit_data(old_stream, 0, 5), Err(RouteError::StaleSession));
        assert_eq!(p.finish_stream(old_stream), Err(RouteError::StaleSession));
        assert_eq!(p.grant_credit(new_stream, 1, 32), Ok(()));
        assert_eq!(p.admit_data(new_stream, 0, 32), Ok(()));
    }

    #[test]
    fn finished_stream_id_reuse_never_accepts_old_callbacks() {
        let mut p = connected();
        let session = p.test_session();
        let first = p.open_stream(session, Direction::Forward, 7, 1, 10).unwrap();
        assert_eq!(p.finish_stream(first), Ok(()));
        let second = p.open_stream(session, Direction::Forward, 7, 1, 10).unwrap();
        assert_ne!(first, second);
        assert_eq!(p.grant_credit(first, 1, 10), Err(RouteError::StaleStream));
        assert_eq!(p.admit_data(first, 0, 1), Err(RouteError::StaleStream));
        assert_eq!(p.finish_stream(first), Err(RouteError::StaleStream));
        assert_eq!(p.grant_credit(second, 1, 10), Ok(()));
        assert_eq!(p.admit_data(second, 0, 10), Ok(()));
        assert_eq!(p.finish_stream(second), Ok(()));
    }

}
