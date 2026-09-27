use std::{
    collections::HashMap,
    net::{IpAddr, SocketAddr},
    num::NonZeroU32,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, anyhow, bail};
use base64::Engine as _;
use bta_anywhere_coordinator_protocol::{Expected, Mode, TicketClaims};
use governor::{DefaultDirectRateLimiter, Quota, RateLimiter};
use quinn::Connection;
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, copy},
    net::{TcpListener, TcpStream},
    sync::{Mutex as AsyncMutex, RwLock, watch},
    time,
};
use tokio_rustls::TlsAcceptor;
use tokio_util::sync::CancellationToken;
use tracing::{debug, info, warn};

use crate::{
    config::RelayConfig,
    metrics::RelayMetrics,
    protocol::{ConnectionOpen, ENCRYPTED_VERSION, GUEST_ALPN, PROTOCOL_VERSION, write_json},
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
    guest_tls: TlsAcceptor,
    pub token_hashes: Vec<[u8; 32]>,
    pub sessions: RwLock<HashMap<String, Arc<Session>>>,
    pub metrics: RelayMetrics,
    pub ready: watch::Sender<bool>,
    pub shutdown: CancellationToken,
    registration_lock: AsyncMutex<()>,
    source_limiters: Mutex<HashMap<IpAddr, SourceLimiter>>,
    coordinator: Option<CoordinatorClient>,
}

struct CoordinatorClient {
    relay_id: String,
    url: reqwest::Url,
    credential: String,
    keys: HashMap<String, Vec<u8>>,
    managed_port_start: u16,
    managed_port_end: u16,
    http: reqwest::Client,
    healthy: AtomicBool,
}

#[derive(Clone)]
struct ManagedLease {
    lease_id: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BoundPort<'a> {
    lease_id: &'a str,
    port: u16,
    generation: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Heartbeat<'a> {
    bound_ports: Vec<BoundPort<'a>>,
    capacity: usize,
}

#[derive(Deserialize)]
struct HeartbeatReply {
    healthy: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Redeem<'a> {
    ticket: &'a str,
    relay_id: &'a str,
    client_instance_id: &'a str,
    mode: Mode,
    public_port: u16,
    generation: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RedeemReply {
    lease_id: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Release<'a> {
    lease_id: &'a str,
    relay_id: &'a str,
    public_port: u16,
    generation: u64,
}

#[derive(Deserialize)]
struct ReleaseReply {
    released: bool,
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
    pub mode: SessionMode,
    managed: Option<ManagedLease>,
    resume_token_hash: RwLock<[u8; 32]>,
    connection: RwLock<Option<Connection>>,
    generation: AtomicU64,
    coordinator_generation: AtomicU64,
    active_connections: AtomicUsize,
    completions: Mutex<HashMap<String, Arc<StreamCompletion>>>,
    overall_accept_limiter: DefaultDirectRateLimiter,
    cancel: CancellationToken,
    listener_stopped: watch::Sender<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionMode {
    Legacy,
    Encrypted,
}

impl SessionMode {
    pub fn version(self) -> u16 {
        match self {
            Self::Legacy => PROTOCOL_VERSION,
            Self::Encrypted => ENCRYPTED_VERSION,
        }
    }

    fn ticket_mode(self) -> Mode {
        match self {
            Self::Legacy => Mode::Legacy,
            Self::Encrypted => Mode::Encrypted,
        }
    }
}

impl CoordinatorClient {
    fn new(config: &crate::config::CoordinatorConfig) -> Result<Self> {
        let credential = std::fs::read_to_string(&config.credential_path)
            .with_context(|| {
                format!(
                    "cannot read coordinator credential file {}",
                    config.credential_path.display()
                )
            })?
            .trim_end_matches(['\r', '\n'])
            .to_owned();
        if credential.len() < 32
            || credential.len() > 256
            || !credential.bytes().all(|b| b.is_ascii_graphic())
        {
            bail!("coordinator credential must contain 32-256 printable ASCII bytes");
        }
        let mut builder = reqwest::Client::builder()
            .timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none());
        if let Some(path) = &config.ca_certificate_path {
            let pem = std::fs::read(path)
                .with_context(|| format!("cannot read coordinator CA file {}", path.display()))?;
            builder = builder.add_root_certificate(
                reqwest::Certificate::from_pem(&pem)
                    .context("invalid coordinator CA certificate")?,
            );
        }
        let keys = config
            .signing_keys
            .iter()
            .map(|(id, encoded)| {
                let bytes = base64::engine::general_purpose::STANDARD
                    .decode(encoded)
                    .context("invalid coordinator signing key")?;
                Ok((id.clone(), bytes))
            })
            .collect::<Result<HashMap<_, _>>>()?;
        Ok(Self {
            relay_id: config.relay_id.clone(),
            url: reqwest::Url::parse(&config.url).context("invalid coordinator URL")?,
            credential,
            keys,
            managed_port_start: config.managed_port_start,
            managed_port_end: config.managed_port_end,
            http: builder
                .build()
                .context("cannot create coordinator HTTPS client")?,
            healthy: AtomicBool::new(false),
        })
    }

    fn endpoint(&self, path: &str) -> Result<reqwest::Url> {
        self.url.join(path).context("invalid coordinator endpoint")
    }

    async fn post<T: Serialize, R: for<'de> Deserialize<'de>>(
        &self,
        path: &str,
        body: &T,
    ) -> Result<R> {
        let response = self
            .http
            .post(self.endpoint(path)?)
            .bearer_auth(&self.credential)
            .json(body)
            .send()
            .await
            .context("coordinator request failed")?;
        if !response.status().is_success() {
            bail!(
                "coordinator rejected request with HTTP {}",
                response.status()
            );
        }
        response
            .json()
            .await
            .context("invalid coordinator response")
    }

    fn verify_ticket(
        &self,
        ticket: &str,
        client_instance_id: &str,
        mode: SessionMode,
    ) -> Result<TicketClaims> {
        // A bounded, unsigned parse supplies only the candidate port. No claim is trusted until
        // the canonical signature and all exact bindings are checked by the protocol crate.
        let claims = bta_anywhere_coordinator_protocol::inspect_unverified(ticket)
            .context("invalid allocation ticket")?;
        if claims.public_port < self.managed_port_start
            || claims.public_port > self.managed_port_end
        {
            bail!("allocation ticket is outside the managed port range");
        }
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock predates UNIX epoch")?
            .as_millis();
        let now_ms = i64::try_from(now_ms).context("system clock cannot be represented")?;
        bta_anywhere_coordinator_protocol::verify(
            ticket,
            &self.keys,
            Expected {
                relay_id: &self.relay_id,
                public_port: claims.public_port,
                client_instance_id,
                mode: mode.ticket_mode(),
            },
            now_ms,
        )
        .context("allocation ticket signature or binding rejected")
    }

    async fn redeem(&self, ticket: &str, claims: &TicketClaims) -> Result<String> {
        let result: RedeemReply = self
            .post(
                "/v1/leases/redeem",
                &Redeem {
                    ticket,
                    relay_id: &self.relay_id,
                    client_instance_id: &claims.client_instance_id,
                    mode: claims.mode,
                    public_port: claims.public_port,
                    generation: 1,
                },
            )
            .await?;
        if result.lease_id != claims.lease_id {
            bail!("coordinator redeemed a different lease");
        }
        Ok(result.lease_id)
    }

    async fn release(&self, lease: &ManagedLease, port: u16, generation: u64) -> Result<()> {
        let reply: ReleaseReply = self
            .post(
                "/v1/leases/release",
                &Release {
                    lease_id: &lease.lease_id,
                    relay_id: &self.relay_id,
                    public_port: port,
                    generation,
                },
            )
            .await?;
        if !reply.released {
            bail!("coordinator did not confirm release");
        }
        Ok(())
    }
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

pub struct RegistrationInput {
    pub client_instance_id: String,
    pub resume_token: Option<String>,
    pub allocation_ticket: Option<String>,
}

impl RelayState {
    pub fn new(config: RelayConfig, guest_tls: TlsAcceptor) -> Result<Self> {
        let coordinator = config
            .coordinator
            .as_ref()
            .map(CoordinatorClient::new)
            .transpose()?;
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
                guest_tls,
                token_hashes,
                sessions: RwLock::new(HashMap::new()),
                metrics: RelayMetrics::new(),
                ready,
                shutdown: CancellationToken::new(),
                registration_lock: AsyncMutex::new(()),
                source_limiters: Mutex::new(HashMap::new()),
                coordinator,
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

    pub async fn coordinator_heartbeat(&self) -> Result<()> {
        let Some(coordinator) = &self.inner.coordinator else {
            return Ok(());
        };
        let _registration_guard = self.inner.registration_lock.lock().await;
        let sessions = self.inner.sessions.read().await;
        let managed_sessions = sessions
            .values()
            .filter_map(|session| {
                session
                    .managed
                    .as_ref()
                    .map(|_| (session.clone(), session.generation.load(Ordering::SeqCst)))
            })
            .collect::<Vec<_>>();
        let bound_ports = managed_sessions
            .iter()
            .map(|(session, generation)| BoundPort {
                lease_id: &session.managed.as_ref().expect("managed session").lease_id,
                port: session.public_port,
                generation: *generation,
            })
            .collect::<Vec<_>>();
        let reply: Result<HeartbeatReply> = coordinator
            .post(
                &format!("/v1/relays/{}/heartbeat", coordinator.relay_id),
                &Heartbeat {
                    bound_ports,
                    capacity: usize::from(
                        coordinator.managed_port_end - coordinator.managed_port_start,
                    ) + 1,
                },
            )
            .await;
        coordinator.healthy.store(
            matches!(&reply, Ok(value) if value.healthy),
            Ordering::SeqCst,
        );
        match reply {
            Ok(value) if value.healthy => {
                for (session, generation) in managed_sessions {
                    session
                        .coordinator_generation
                        .store(generation, Ordering::SeqCst);
                }
                Ok(())
            }
            Ok(_) => bail!("coordinator did not confirm relay reconciliation"),
            Err(error) => Err(error),
        }
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
        input: RegistrationInput,
        connection: Connection,
        mode: SessionMode,
    ) -> Result<Registration> {
        let RegistrationInput {
            client_instance_id,
            resume_token,
            allocation_ticket,
        } = input;
        if client_instance_id.is_empty() || client_instance_id.len() > 128 {
            bail!("client instance ID must contain between 1 and 128 characters");
        }
        if let Some(resume_token) = resume_token {
            if allocation_ticket.is_some() {
                bail!("resume cannot redeem a new allocation ticket");
            }
            return self
                .resume(
                    access_token_hash,
                    source_ip,
                    client_instance_id,
                    &resume_token,
                    connection,
                    mode,
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

        let (listener, public_port, managed) = if let Some(ticket) = allocation_ticket {
            let coordinator = self
                .inner
                .coordinator
                .as_ref()
                .context("managed registration is not enabled")?;
            if !coordinator.healthy.load(Ordering::SeqCst) {
                bail!("coordinator has not confirmed relay reconciliation");
            }
            let claims = coordinator.verify_ticket(&ticket, &client_instance_id, mode)?;
            let lease_id = coordinator.redeem(&ticket, &claims).await?;
            let lease = ManagedLease { lease_id };
            match self.bind_managed_port(claims.public_port).await {
                Ok(listener) => (listener, claims.public_port, Some(lease)),
                Err(error) => {
                    if let Err(release_error) =
                        coordinator.release(&lease, claims.public_port, 1).await
                    {
                        warn!(port = claims.public_port, %release_error, "failed to release redeemed lease after bind failure");
                    }
                    return Err(error);
                }
            }
        } else {
            let (listener, port) = self.bind_available_port().await?;
            (listener, port, None)
        };
        let id = token::generate();
        let resume_token = token::generate();
        let max_accepts = self.inner.config.max_accepts_per_minute;
        let (listener_stopped, _) = watch::channel(false);
        let session = Arc::new(Session {
            id: id.clone(),
            public_port,
            source_ip,
            client_instance_id,
            access_token_hash,
            mode,
            managed,
            resume_token_hash: RwLock::new(token::hash(&resume_token)),
            connection: RwLock::new(Some(connection)),
            generation: AtomicU64::new(1),
            coordinator_generation: AtomicU64::new(1),
            active_connections: AtomicUsize::new(0),
            completions: Mutex::new(HashMap::new()),
            overall_accept_limiter: RateLimiter::direct(Quota::per_minute(
                NonZeroU32::new(max_accepts.saturating_mul(8)).expect("validated non-zero"),
            )),
            cancel: self.inner.shutdown.child_token(),
            listener_stopped,
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
        mode: SessionMode,
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
        if session.mode != mode {
            bail!("resume token belongs to another guest transport mode");
        }
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

    async fn bind_managed_port(&self, port: u16) -> Result<TcpListener> {
        let coordinator = self
            .inner
            .coordinator
            .as_ref()
            .context("managed registration is not enabled")?;
        if port < coordinator.managed_port_start || port > coordinator.managed_port_end {
            bail!("managed port is outside configured range");
        }
        if self
            .inner
            .sessions
            .read()
            .await
            .values()
            .any(|session| session.public_port == port)
        {
            bail!("managed port already has a relay session");
        }
        let address = SocketAddr::new(self.inner.config.tcp_bind_ip, port);
        TcpListener::bind(address)
            .await
            .with_context(|| format!("could not bind redeemed managed port {port}"))
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
        drop(listener);
        if let (Some(coordinator), Some(lease)) = (&self.inner.coordinator, &session.managed) {
            if let Err(error) = coordinator
                .release(
                    lease,
                    session.public_port,
                    session.coordinator_generation.load(Ordering::SeqCst),
                )
                .await
            {
                warn!(port = session.public_port, %error, "managed lease release not confirmed; coordinator will quarantine until reconciliation");
            }
        }
        session.listener_stopped.send_replace(true);
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
            let result = match session.mode {
                SessionMode::Legacy => {
                    state
                        .forward_guest(session.clone(), connection, stream, remote)
                        .await
                }
                SessionMode::Encrypted => {
                    match time::timeout(
                        Duration::from_secs(10),
                        state.inner.guest_tls.accept(stream),
                    )
                    .await
                    {
                        Ok(Ok(tls)) if tls.get_ref().1.alpn_protocol() == Some(GUEST_ALPN) => {
                            state
                                .forward_guest(session.clone(), connection, tls, remote)
                                .await
                        }
                        _ => Err(anyhow!("encrypted guest TLS handshake or ALPN failed")),
                    }
                }
            };
            if let Err(error) = result {
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

    async fn forward_guest<S>(
        &self,
        session: Arc<Session>,
        connection: Connection,
        stream: S,
        remote: SocketAddr,
    ) -> Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let (mut quic_send, mut quic_recv) =
            time::timeout(Duration::from_secs(10), connection.open_bi())
                .await
                .context("timed out opening QUIC stream")??;
        let header = ConnectionOpen {
            version: session.mode.version(),
            session_id: session.id.clone(),
            connection_id: token::generate(),
            remote_address: remote.to_string(),
        };
        let tracked = if session.mode == SessionMode::Legacy {
            Some(session.track_stream(
                header.connection_id.clone(),
                self.inner.config.max_connections_per_session,
            )?)
        } else {
            None
        };
        write_json(&mut quic_send, &header).await?;
        let trace_id = header.connection_id;
        trace_eof(
            &trace_id,
            "relay_opened",
            None,
            Some((remote.port(), quic_send.id().into())),
        );

        let (mut tcp_read, mut tcp_write) = tokio::io::split(stream);
        let guest_to_host = async {
            let bytes = copy(&mut tcp_read, &mut quic_send).await?;
            trace_eof(&trace_id, "relay_guest_tcp_eof", Some(bytes), None);
            quic_send.finish()?;
            trace_eof(&trace_id, "relay_quic_send_finish", None, None);
            Ok::<u64, anyhow::Error>(bytes)
        };
        let host_to_guest = async {
            if let Some((completion, _)) = &tracked {
                copy_response_with_completion(&mut quic_recv, &mut tcp_write, completion, &trace_id)
                    .await
            } else {
                let bytes = copy(&mut quic_recv, &mut tcp_write).await?;
                tcp_write.shutdown().await?;
                Ok(bytes)
            }
        };
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
            candidate.cancel.cancel();
            let mut stopped = candidate.listener_stopped.subscribe();
            while !*stopped.borrow() {
                if stopped.changed().await.is_err() {
                    return;
                }
            }
            if sessions
                .get(id)
                .is_some_and(|current| Arc::ptr_eq(current, &candidate))
            {
                sessions.remove(id);
                self.inner.metrics.active_sessions.dec();
                info!(port = candidate.public_port, "released relay session");
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
        for session in &sessions {
            if let Some(connection) = session.connection.write().await.take() {
                connection.close(0_u32.into(), b"relay shutdown");
            }
            session.cancel.cancel();
            self.inner.metrics.active_sessions.dec();
        }
        for session in sessions {
            let mut stopped = session.listener_stopped.subscribe();
            let _ = time::timeout(Duration::from_secs(7), async {
                while !*stopped.borrow() {
                    if stopped.changed().await.is_err() {
                        break;
                    }
                }
            })
            .await;
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
        let mut written = 0;
        while written < received {
            let chunk_end = bytes
                .checked_add((received - written) as u64)
                .context("response byte count overflow")?;
            if completion
                .target()?
                .is_some_and(|announced| chunk_end > announced)
            {
                bail!("QUIC response exceeds announced length");
            }
            let count = tokio::select! {
                biased;
                changed = notices.changed() => {
                    changed.context("stream completion notice channel closed")?;
                    continue;
                }
                write = target.write(&buffer[written..received]) => write?,
            };
            if count == 0 {
                bail!("guest TCP write returned zero bytes");
            }
            let next = bytes
                .checked_add(count as u64)
                .context("response byte count overflow")?;
            completion.copied(next)?;
            bytes = next;
            written += count;
        }
    }
}

#[cfg(test)]
mod completion_tests {
    use super::*;
    use std::net::Ipv4Addr;
    use tokio::io::AsyncReadExt;

    fn session() -> Arc<Session> {
        Arc::new(Session {
            mode: SessionMode::Legacy,
            managed: None,
            id: "test-session".into(),
            public_port: 30_000,
            source_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            client_instance_id: "test-client".into(),
            access_token_hash: [0; 32],
            resume_token_hash: RwLock::new([0; 32]),
            connection: RwLock::new(None),
            generation: AtomicU64::new(1),
            coordinator_generation: AtomicU64::new(1),
            active_connections: AtomicUsize::new(0),
            completions: Mutex::new(HashMap::new()),
            overall_accept_limiter: RateLimiter::direct(Quota::per_minute(
                NonZeroU32::new(1).unwrap(),
            )),
            cancel: CancellationToken::new(),
            listener_stopped: watch::channel(true).0,
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
    async fn invalid_notice_interrupts_a_blocked_partial_guest_write() {
        let completion = Arc::new(StreamCompletion::new());
        let (mut source_write, mut source_read) = tokio::io::duplex(64);
        let (mut guest_write, _guest_read) = tokio::io::duplex(2);
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
        source_write.write_all(b"12345678").await.unwrap();
        time::timeout(Duration::from_secs(1), async {
            loop {
                if completion.state.lock().unwrap().copied == 2 {
                    break;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("guest write never reached backpressure");
        assert_eq!(completion.notice(8), NoticeResult::Accepted);
        assert_eq!(completion.notice(8), NoticeResult::Rejected);
        assert!(
            time::timeout(Duration::from_secs(1), worker)
                .await
                .expect("invalid notice did not interrupt blocked write")
                .unwrap()
                .is_err()
        );
        assert_eq!(completion.state.lock().unwrap().copied, 2);
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

#[cfg(test)]
mod managed_ticket_tests {
    use super::*;
    use bta_anywhere_coordinator_protocol::{TicketClaims, public_key_bytes, sign};
    use ring::{rand::SystemRandom, signature::Ed25519KeyPair};

    #[test]
    fn relay_requires_signed_exact_ticket_bindings_before_redemption() {
        let key_bytes = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new()).unwrap();
        let key = Ed25519KeyPair::from_pkcs8(key_bytes.as_ref()).unwrap();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64;
        let mut claims = TicketClaims {
            version: 1,
            lease_id: base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([7_u8; 16]),
            key_id: "key-1".into(),
            relay_id: "eu-west-1".into(),
            public_port: 30_500,
            client_instance_id: "host-1".into(),
            mode: Mode::Encrypted,
            issued_at_epoch_millis: now - 1000,
            expires_at_epoch_millis: now + 20_000,
        };
        let dir = tempfile::tempdir().unwrap();
        let credential = dir.path().join("relay.credential");
        std::fs::write(&credential, "test-relay-credential-long-enough\n").unwrap();
        let mut config = crate::config::CoordinatorConfig {
            relay_id: "eu-west-1".into(),
            url: "https://localhost:9443".into(),
            credential_path: credential,
            ca_certificate_path: None,
            signing_keys: HashMap::from([(
                "key-1".into(),
                base64::engine::general_purpose::STANDARD.encode(public_key_bytes(&key)),
            )]),
            managed_port_start: 30_500,
            managed_port_end: 30_599,
            heartbeat_seconds: 5,
        };
        let ticket = sign(&claims, &key).unwrap();
        let verifier = CoordinatorClient::new(&config).unwrap();
        assert_eq!(
            verifier
                .verify_ticket(&ticket, "host-1", SessionMode::Encrypted)
                .unwrap(),
            claims
        );
        assert!(
            verifier
                .verify_ticket(&ticket, "wrong-host", SessionMode::Encrypted)
                .is_err()
        );
        assert!(
            verifier
                .verify_ticket(&ticket, "host-1", SessionMode::Legacy)
                .is_err()
        );
        assert!(
            verifier
                .verify_ticket("BTACT1:malformed", "host-1", SessionMode::Encrypted)
                .is_err()
        );
        let mut altered = ticket.into_bytes();
        let last = altered.len() - 1;
        altered[last] = if altered[last] == b'A' { b'B' } else { b'A' };
        assert!(
            verifier
                .verify_ticket(
                    &String::from_utf8(altered).unwrap(),
                    "host-1",
                    SessionMode::Encrypted
                )
                .is_err()
        );
        config.relay_id = "different-relay".into();
        assert!(
            CoordinatorClient::new(&config)
                .unwrap()
                .verify_ticket(
                    &sign(&claims, &key).unwrap(),
                    "host-1",
                    SessionMode::Encrypted
                )
                .is_err()
        );
        config.relay_id = "eu-west-1".into();
        config.managed_port_start = 30_501;
        assert!(
            CoordinatorClient::new(&config)
                .unwrap()
                .verify_ticket(
                    &sign(&claims, &key).unwrap(),
                    "host-1",
                    SessionMode::Encrypted
                )
                .is_err()
        );
        claims.issued_at_epoch_millis = now - 20_000;
        claims.expires_at_epoch_millis = now - 1;
        config.managed_port_start = 30_500;
        assert!(
            CoordinatorClient::new(&config)
                .unwrap()
                .verify_ticket(
                    &sign(&claims, &key).unwrap(),
                    "host-1",
                    SessionMode::Encrypted
                )
                .is_err()
        );
    }
}
