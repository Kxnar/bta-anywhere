use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    num::NonZeroU32,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::{Context, Result, anyhow, bail};
use governor::{DefaultDirectRateLimiter, Quota, RateLimiter};
use quinn::Connection;
use subtle::ConstantTimeEq;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, copy},
    net::{TcpListener, TcpStream},
    sync::{Mutex as AsyncMutex, RwLock, watch},
    time,
};
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::{
    config::RelayConfig,
    metrics::RelayMetrics,
    protocol::{ConnectionOpen, PROTOCOL_VERSION, write_json},
    token,
};

const EOF_TRACE_LIMIT: usize = 4096;
static EOF_TRACE_ENABLED: OnceLock<bool> = OnceLock::new();
static EOF_TRACE_LINES: AtomicUsize = AtomicUsize::new(0);

fn trace_eof(
    connection_id: &str,
    event: &'static str,
    bytes: Option<u64>,
    open: Option<(u16, u64)>,
) {
    if !*EOF_TRACE_ENABLED
        .get_or_init(|| std::env::var("BTA_EOF_TRACE").is_ok_and(|value| value == "1"))
    {
        return;
    }
    if EOF_TRACE_LINES.fetch_add(1, Ordering::Relaxed) >= EOF_TRACE_LIMIT {
        return;
    }
    let digest = token::hash_hex(connection_id);
    match (bytes, open) {
        (Some(bytes), _) => eprintln!(
            "BTA_EOF trace={} event={} bytes={}",
            &digest[..16],
            event,
            bytes
        ),
        (_, Some((port, stream_id))) => eprintln!(
            "BTA_EOF trace={} event={} guest_port={} stream_id={}",
            &digest[..16],
            event,
            port,
            stream_id
        ),
        _ => eprintln!("BTA_EOF trace={} event={}", &digest[..16], event),
    }
}

#[derive(Clone)]
pub struct RelayState {
    inner: Arc<RelayStateInner>,
}

struct RelayStateInner {
    pub config: RelayConfig,
    pub token_hashes: Vec<[u8; 32]>,
    pub sessions: RwLock<HashMap<String, Arc<Session>>>,
    pub metrics: RelayMetrics,
    pub ready: watch::Sender<bool>,
    pub shutdown: CancellationToken,
    registration_lock: AsyncMutex<()>,
    source_limiters: Mutex<HashMap<IpAddr, SourceLimiter>>,
}

struct SourceLimiter {
    limiter: Arc<DefaultDirectRateLimiter>,
    last_seen: Instant,
}

pub struct Session {
    pub id: String,
    pub public_port: u16,
    pub source_ip: IpAddr,
    pub client_instance_id: String,
    pub access_token_hash: [u8; 32],
    resume_token_hash: RwLock<[u8; 32]>,
    connection: RwLock<Option<Connection>>,
    generation: AtomicU64,
    active_connections: AtomicUsize,
    completions: Mutex<HashMap<String, Arc<StreamCompletion>>>,
    overall_accept_limiter: DefaultDirectRateLimiter,
    cancel: CancellationToken,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CompletionSignal {
    Pending,
    Notice(u64),
    Invalid,
}

struct StreamCompletion {
    state: Mutex<CompletionState>,
    signal: watch::Sender<CompletionSignal>,
}

struct CompletionState {
    copied: u64,
    notice: Option<u64>,
    invalid: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub enum NoticeResult {
    Accepted,
    Late,
    Rejected,
}

struct CompletionGuard {
    session: Arc<Session>,
    connection_id: String,
}

impl Drop for CompletionGuard {
    fn drop(&mut self) {
        self.session
            .completions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&self.connection_id);
    }
}

impl StreamCompletion {
    fn new() -> Self {
        let (signal, _) = watch::channel(CompletionSignal::Pending);
        Self {
            state: Mutex::new(CompletionState {
                copied: 0,
                notice: None,
                invalid: false,
            }),
            signal,
        }
    }

    fn notice(&self, bytes: u64) -> NoticeResult {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.invalid || state.notice.is_some() || bytes < state.copied {
            state.invalid = true;
            self.signal.send_replace(CompletionSignal::Invalid);
            return NoticeResult::Rejected;
        }
        state.notice = Some(bytes);
        self.signal.send_replace(CompletionSignal::Notice(bytes));
        NoticeResult::Accepted
    }

    fn copied(&self, bytes: u64) -> Result<()> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.invalid
            || bytes < state.copied
            || state.notice.is_some_and(|target| bytes > target)
        {
            state.invalid = true;
            self.signal.send_replace(CompletionSignal::Invalid);
            bail!("invalid stream completion count");
        }
        state.copied = bytes;
        Ok(())
    }

    fn target(&self) -> Result<Option<u64>> {
        let state = self
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.invalid {
            bail!("invalid stream completion notice");
        }
        Ok(state.notice)
    }
}

impl Session {
    pub fn announce_stream_eof(&self, connection_id: &str, bytes: u64) -> NoticeResult {
        let completion = self
            .completions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(connection_id)
            .cloned();
        match completion {
            Some(completion) => completion.notice(bytes),
            None => NoticeResult::Late,
        }
    }

    fn track_stream(
        self: &Arc<Self>,
        connection_id: String,
        max: usize,
    ) -> Result<(Arc<StreamCompletion>, CompletionGuard)> {
        let completion = Arc::new(StreamCompletion::new());
        let mut entries = self
            .completions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if entries.len() >= max || entries.contains_key(&connection_id) {
            bail!("active stream completion quota exhausted");
        }
        entries.insert(connection_id.clone(), completion.clone());
        Ok((
            completion,
            CompletionGuard {
                session: self.clone(),
                connection_id,
            },
        ))
    }
}

pub struct Registration {
    pub session: Arc<Session>,
    pub resume_token: String,
    pub generation: u64,
    pub resumed: bool,
}

impl RelayState {
    pub fn new(config: RelayConfig) -> Result<Self> {
        let token_hashes = config
            .access_token_hashes
            .iter()
            .map(|value| {
                let decoded = hex::decode(value)?;
                decoded
                    .try_into()
                    .map_err(|_| anyhow!("access token hash must contain 32 bytes"))
            })
            .collect::<Result<Vec<[u8; 32]>>>()?;
        let (ready, _) = watch::channel(false);
        Ok(Self {
            inner: Arc::new(RelayStateInner {
                config,
                token_hashes,
                sessions: RwLock::new(HashMap::new()),
                metrics: RelayMetrics::new(),
                ready,
                shutdown: CancellationToken::new(),
                registration_lock: AsyncMutex::new(()),
                source_limiters: Mutex::new(HashMap::new()),
            }),
        })
    }

    pub fn config(&self) -> &RelayConfig {
        &self.inner.config
    }

    pub fn metrics(&self) -> &RelayMetrics {
        &self.inner.metrics
    }

    pub fn shutdown_token(&self) -> CancellationToken {
        self.inner.shutdown.clone()
    }

    pub fn set_ready(&self, value: bool) {
        self.inner.ready.send_replace(value);
    }

    pub fn is_ready(&self) -> bool {
        *self.inner.ready.borrow()
    }

    pub fn shutdown(&self) {
        self.set_ready(false);
        self.inner.shutdown.cancel();
    }

    pub fn authenticate(&self, access_token: &str) -> Option<[u8; 32]> {
        let candidate = token::hash(access_token);
        self.inner
            .token_hashes
            .iter()
            .find(|configured| token::matches_hash(&candidate, configured))
            .copied()
    }

    pub async fn register(
        &self,
        access_token_hash: [u8; 32],
        source_ip: IpAddr,
        client_instance_id: String,
        resume_token: Option<String>,
        connection: Connection,
    ) -> Result<Registration> {
        if client_instance_id.is_empty() || client_instance_id.len() > 128 {
            bail!("client instance ID must contain between 1 and 128 characters");
        }
        if let Some(resume_token) = resume_token {
            return self
                .resume(
                    access_token_hash,
                    source_ip,
                    client_instance_id,
                    &resume_token,
                    connection,
                )
                .await;
        }

        let _registration_guard = self.inner.registration_lock.lock().await;

        let sessions = self.inner.sessions.read().await;
        let token_count = sessions
            .values()
            .filter(|session| bool::from(session.access_token_hash.ct_eq(&access_token_hash)))
            .count();
        if token_count >= self.inner.config.max_sessions_per_token {
            bail!("session quota for access token is exhausted");
        }
        let source_count = sessions
            .values()
            .filter(|session| session.source_ip == source_ip)
            .count();
        if source_count >= self.inner.config.max_sessions_per_ip {
            bail!("session quota for source address is exhausted");
        }
        drop(sessions);

        let (listener, public_port) = self.bind_available_port().await?;
        let id = token::generate();
        let resume_token = token::generate();
        let max_accepts = self.inner.config.max_accepts_per_minute;
        let session = Arc::new(Session {
            id: id.clone(),
            public_port,
            source_ip,
            client_instance_id,
            access_token_hash,
            resume_token_hash: RwLock::new(token::hash(&resume_token)),
            connection: RwLock::new(Some(connection)),
            generation: AtomicU64::new(1),
            active_connections: AtomicUsize::new(0),
            completions: Mutex::new(HashMap::new()),
            overall_accept_limiter: RateLimiter::direct(Quota::per_minute(
                NonZeroU32::new(max_accepts.saturating_mul(8)).expect("validated non-zero"),
            )),
            cancel: self.inner.shutdown.child_token(),
        });

        self.inner
            .sessions
            .write()
            .await
            .insert(id, session.clone());
        self.inner.metrics.sessions_total.inc();
        self.inner.metrics.active_sessions.inc();

        let state = self.clone();
        let listener_session = session.clone();
        tokio::spawn(async move {
            state.serve_public_port(listener_session, listener).await;
        });

        info!(port = public_port, "registered relay session");
        Ok(Registration {
            session,
            resume_token,
            generation: 1,
            resumed: false,
        })
    }

    async fn resume(
        &self,
        access_token_hash: [u8; 32],
        source_ip: IpAddr,
        client_instance_id: String,
        resume_token: &str,
        connection: Connection,
    ) -> Result<Registration> {
        let candidate = token::hash(resume_token);
        let sessions = self.inner.sessions.read().await;
        let mut matched = None;
        for session in sessions.values() {
            let current = *session.resume_token_hash.read().await;
            if bool::from(current.ct_eq(&candidate)) {
                matched = Some(session.clone());
                break;
            }
        }
        let session = matched.context("resume token is invalid or expired")?;
        if !bool::from(session.access_token_hash.ct_eq(&access_token_hash)) {
            bail!("resume token does not belong to this access token");
        }
        if session.client_instance_id != client_instance_id {
            bail!("resume token does not belong to this client instance");
        }
        if session.source_ip != source_ip {
            debug!(old = %session.source_ip, new = %source_ip, "session resumed from a new address");
        }

        let mut current_connection = session.connection.write().await;
        if current_connection.is_some() {
            bail!("session is already connected");
        }
        *current_connection = Some(connection);
        drop(current_connection);
        drop(sessions);

        let new_resume_token = token::generate();
        *session.resume_token_hash.write().await = token::hash(&new_resume_token);
        let generation = session.generation.fetch_add(1, Ordering::SeqCst) + 1;
        info!(port = session.public_port, "resumed relay session");
        Ok(Registration {
            session,
            resume_token: new_resume_token,
            generation,
            resumed: true,
        })
    }

    async fn bind_available_port(&self) -> Result<(TcpListener, u16)> {
        for port in self.inner.config.tcp_port_start..=self.inner.config.tcp_port_end {
            let already_allocated = self
                .inner
                .sessions
                .read()
                .await
                .values()
                .any(|session| session.public_port == port);
            if already_allocated {
                continue;
            }
            let address = SocketAddr::new(self.inner.config.tcp_bind_ip, port);
            match TcpListener::bind(address).await {
                Ok(listener) => return Ok((listener, port)),
                Err(error) => debug!(%address, %error, "relay TCP port unavailable"),
            }
        }
        self.inner.metrics.rejected_total.inc();
        bail!("no TCP ports are available in the configured pool")
    }

    async fn serve_public_port(&self, session: Arc<Session>, listener: TcpListener) {
        loop {
            tokio::select! {
                _ = session.cancel.cancelled() => break,
                accepted = listener.accept() => {
                    match accepted {
                        Ok((stream, remote)) => self.accept_guest(session.clone(), stream, remote).await,
                        Err(error) => {
                            warn!(port = session.public_port, %error, "failed to accept guest connection");
                            time::sleep(Duration::from_millis(100)).await;
                        }
                    }
                }
            }
        }
        debug!(port = session.public_port, "public TCP listener stopped");
    }

    async fn accept_guest(&self, session: Arc<Session>, stream: TcpStream, remote: SocketAddr) {
        if session.overall_accept_limiter.check().is_err() || !self.check_source_limit(remote.ip())
        {
            self.inner.metrics.rejected_total.inc();
            return;
        }
        let previous = session.active_connections.fetch_add(1, Ordering::SeqCst);
        if previous >= self.inner.config.max_connections_per_session {
            session.active_connections.fetch_sub(1, Ordering::SeqCst);
            self.inner.metrics.rejected_total.inc();
            return;
        }

        let connection = session.connection.read().await.clone();
        let Some(connection) = connection else {
            session.active_connections.fetch_sub(1, Ordering::SeqCst);
            self.inner.metrics.rejected_total.inc();
            return;
        };

        self.inner.metrics.connections_total.inc();
        self.inner.metrics.active_connections.inc();
        let state = self.clone();
        tokio::spawn(async move {
            if let Err(error) = state
                .forward_guest(session.clone(), connection, stream, remote)
                .await
            {
                debug!(port = session.public_port, %remote, %error, "guest tunnel closed with an error");
            }
            session.active_connections.fetch_sub(1, Ordering::SeqCst);
            state.inner.metrics.active_connections.dec();
        });
    }

    fn check_source_limit(&self, source: IpAddr) -> bool {
        let mut limiters = self
            .inner
            .source_limiters
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !limiters.contains_key(&source) && limiters.len() >= 4096 {
            let expiry = Instant::now() - Duration::from_secs(10 * 60);
            limiters.retain(|_, limiter| limiter.last_seen >= expiry);
            if limiters.len() >= 4096 {
                return false;
            }
        }
        let max_accepts = self.inner.config.max_accepts_per_minute;
        let limiter = limiters.entry(source).or_insert_with(|| SourceLimiter {
            limiter: Arc::new(RateLimiter::direct(Quota::per_minute(
                NonZeroU32::new(max_accepts).expect("validated non-zero"),
            ))),
            last_seen: Instant::now(),
        });
        limiter.last_seen = Instant::now();
        limiter.limiter.check().is_ok()
    }

    async fn forward_guest(
        &self,
        session: Arc<Session>,
        connection: Connection,
        stream: TcpStream,
        remote: SocketAddr,
    ) -> Result<()> {
        let (mut quic_send, mut quic_recv) =
            time::timeout(Duration::from_secs(10), connection.open_bi())
                .await
                .context("timed out opening QUIC stream")??;
        let header = ConnectionOpen {
            version: PROTOCOL_VERSION,
            session_id: session.id.clone(),
            connection_id: token::generate(),
            remote_address: remote.to_string(),
        };
        let (completion, _completion_guard) = session.track_stream(
            header.connection_id.clone(),
            self.inner.config.max_connections_per_session,
        )?;
        write_json(&mut quic_send, &header).await?;
        let trace_id = header.connection_id;
        trace_eof(
            &trace_id,
            "relay_opened",
            None,
            Some((remote.port(), quic_send.id().into())),
        );

        let (mut tcp_read, mut tcp_write) = stream.into_split();
        let guest_to_host = async {
            let bytes = copy(&mut tcp_read, &mut quic_send).await?;
            trace_eof(&trace_id, "relay_guest_tcp_eof", Some(bytes), None);
            quic_send.finish()?;
            trace_eof(&trace_id, "relay_quic_send_finish", None, None);
            Ok::<u64, anyhow::Error>(bytes)
        };
        let host_to_guest =
            copy_response_with_completion(&mut quic_recv, &mut tcp_write, &completion, &trace_id);
        let result = tokio::try_join!(guest_to_host, host_to_guest);
        if result.is_err() {
            trace_eof(&trace_id, "relay_forward_error", None, None);
        }
        let (guest_bytes, host_bytes) = result?;
        trace_eof(&trace_id, "relay_complete", None, None);
        self.inner.metrics.bytes_guest_to_host.inc_by(guest_bytes);
        self.inner.metrics.bytes_host_to_guest.inc_by(host_bytes);
        Ok(())
    }

    pub async fn detach(&self, session: Arc<Session>, generation: u64, immediate: bool) {
        let mut connection = session.connection.write().await;
        if session.generation.load(Ordering::SeqCst) != generation {
            return;
        }
        *connection = None;
        drop(connection);
        if immediate {
            self.remove_session(&session.id, generation).await;
            return;
        }
        let state = self.clone();
        tokio::spawn(async move {
            time::sleep(Duration::from_secs(state.inner.config.resume_grace_seconds)).await;
            state.remove_session(&session.id, generation).await;
        });
    }

    async fn remove_session(&self, id: &str, generation: u64) {
        let mut sessions = self.inner.sessions.write().await;
        let Some(candidate) = sessions.get(id).cloned() else {
            return;
        };
        if candidate.generation.load(Ordering::SeqCst) != generation
            || candidate.connection.read().await.is_some()
        {
            return;
        }
        let still_same = sessions
            .get(id)
            .is_some_and(|current| Arc::ptr_eq(current, &candidate));
        if still_same {
            if let Some(session) = sessions.remove(id) {
                session.cancel.cancel();
                self.inner.metrics.active_sessions.dec();
                info!(port = session.public_port, "released relay session");
            }
        }
    }

    pub async fn close_all(&self) {
        let sessions = self
            .inner
            .sessions
            .write()
            .await
            .drain()
            .map(|(_, session)| session)
            .collect::<Vec<_>>();
        for session in sessions {
            if let Some(connection) = session.connection.write().await.take() {
                connection.close(0_u32.into(), b"relay shutdown");
            }
            session.cancel.cancel();
            self.inner.metrics.active_sessions.dec();
        }
    }
}

async fn copy_response_with_completion<R, W>(
    source: &mut R,
    target: &mut W,
    completion: &StreamCompletion,
    trace_id: &str,
) -> Result<u64>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut notices = completion.signal.subscribe();
    let mut buffer = [0_u8; 16 * 1024];
    let mut bytes = 0_u64;
    loop {
        if completion.target()? == Some(bytes) {
            trace_eof(trace_id, "relay_control_eof", Some(bytes), None);
            target.shutdown().await?;
            trace_eof(trace_id, "relay_tcp_shutdown_complete", None, None);
            return Ok(bytes);
        }
        let received = tokio::select! {
            changed = notices.changed() => {
                changed.context("stream completion notice channel closed")?;
                continue;
            }
            read = source.read(&mut buffer) => read?,
        };
        if received == 0 {
            if completion
                .target()?
                .is_some_and(|announced| announced != bytes)
            {
                bail!("QUIC stream ended before announced response length");
            }
            trace_eof(trace_id, "relay_quic_recv_eof", Some(bytes), None);
            target.shutdown().await?;
            trace_eof(trace_id, "relay_tcp_shutdown_complete", None, None);
            return Ok(bytes);
        }
        let next = bytes
            .checked_add(received as u64)
            .context("response byte count overflow")?;
        if completion
            .target()?
            .is_some_and(|announced| next > announced)
        {
            bail!("QUIC response exceeds announced length");
        }
        target.write_all(&buffer[..received]).await?;
        completion.copied(next)?;
        bytes = next;
    }
}

#[cfg(test)]
mod completion_tests {
    use super::*;
    use std::net::Ipv4Addr;
    use tokio::io::AsyncReadExt;

    fn session() -> Arc<Session> {
        Arc::new(Session {
            id: "test-session".into(),
            public_port: 30_000,
            source_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            client_instance_id: "test-client".into(),
            access_token_hash: [0; 32],
            resume_token_hash: RwLock::new([0; 32]),
            connection: RwLock::new(None),
            generation: AtomicU64::new(1),
            active_connections: AtomicUsize::new(0),
            completions: Mutex::new(HashMap::new()),
            overall_accept_limiter: RateLimiter::direct(Quota::per_minute(
                NonZeroU32::new(1).unwrap(),
            )),
            cancel: CancellationToken::new(),
        })
    }

    #[test]
    fn notices_are_session_scoped_bounded_and_removed_on_cancel() {
        let owner = session();
        let other = session();
        let (completion, guard) = owner.track_stream("one".into(), 1).unwrap();
        assert!(owner.track_stream("two".into(), 1).is_err());
        assert_eq!(other.announce_stream_eof("one", 4), NoticeResult::Late);
        assert_eq!(completion.target().unwrap(), None);
        assert_eq!(owner.announce_stream_eof("one", 4), NoticeResult::Accepted);
        assert_eq!(owner.announce_stream_eof("one", 4), NoticeResult::Rejected);
        assert!(completion.target().is_err());
        drop(guard);
        assert_eq!(owner.announce_stream_eof("one", 4), NoticeResult::Late);
        assert!(owner.completions.lock().unwrap().is_empty());
    }

    #[test]
    fn notice_below_copied_bytes_fails_closed() {
        let completion = StreamCompletion::new();
        completion.copied(5).unwrap();
        assert_eq!(completion.notice(4), NoticeResult::Rejected);
        assert!(completion.target().is_err());
    }

    #[tokio::test]
    async fn exact_notice_closes_output_without_native_fin() {
        let completion = Arc::new(StreamCompletion::new());
        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, mut guest_read) = tokio::io::duplex(64);
        let worker_completion = completion.clone();
        let worker = tokio::spawn(async move {
            copy_response_with_completion(
                &mut source_read,
                &mut guest_write,
                &worker_completion,
                "test",
            )
            .await
        });
        source_write.write_all(b"hello").await.unwrap();
        let mut received = [0; 5];
        guest_read.read_exact(&mut received).await.unwrap();
        assert_eq!(&received, b"hello");
        assert_eq!(completion.notice(5), NoticeResult::Accepted);
        assert_eq!(
            time::timeout(Duration::from_secs(1), worker)
                .await
                .unwrap()
                .unwrap()
                .unwrap(),
            5
        );
        assert_eq!(guest_read.read(&mut received).await.unwrap(), 0);
        // The source writer remains open, modelling the missing QUIC FIN.
        drop(source_write);
    }

    #[tokio::test]
    async fn notice_before_response_bytes_closes_at_exact_count() {
        let completion = StreamCompletion::new();
        assert_eq!(completion.notice(5), NoticeResult::Accepted);
        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, mut guest_read) = tokio::io::duplex(64);
        source_write.write_all(b"hello").await.unwrap();
        let copied =
            copy_response_with_completion(&mut source_read, &mut guest_write, &completion, "test")
                .await
                .unwrap();
        assert_eq!(copied, 5);
        let mut received = [0; 5];
        guest_read.read_exact(&mut received).await.unwrap();
        assert_eq!(&received, b"hello");
        assert_eq!(guest_read.read(&mut received).await.unwrap(), 0);
    }

    #[tokio::test]
    async fn native_fin_and_short_or_excess_notice_are_checked() {
        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, mut guest_read) = tokio::io::duplex(64);
        source_write.write_all(b"hello").await.unwrap();
        drop(source_write);
        let native = StreamCompletion::new();
        assert_eq!(
            copy_response_with_completion(&mut source_read, &mut guest_write, &native, "test")
                .await
                .unwrap(),
            5
        );
        let mut received = [0; 5];
        guest_read.read_exact(&mut received).await.unwrap();
        assert_eq!(guest_read.read(&mut received).await.unwrap(), 0);

        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, _guest_read) = tokio::io::duplex(64);
        source_write.write_all(b"short").await.unwrap();
        drop(source_write);
        let short = StreamCompletion::new();
        assert_eq!(short.notice(6), NoticeResult::Accepted);
        assert!(
            copy_response_with_completion(&mut source_read, &mut guest_write, &short, "test")
                .await
                .is_err()
        );

        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, _guest_read) = tokio::io::duplex(64);
        source_write.write_all(b"excess").await.unwrap();
        let excess = StreamCompletion::new();
        assert_eq!(excess.notice(5), NoticeResult::Accepted);
        assert!(
            copy_response_with_completion(&mut source_read, &mut guest_write, &excess, "test")
                .await
                .is_err()
        );
    }
}
