//! Single-owner, durable managed-port allocator. The HTTP layer supplies authenticated callers.

use std::{
    collections::{HashMap, HashSet},
    fs::{File, OpenOptions},
    path::Path,
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

use bta_anywhere_coordinator_protocol::{
    Expected, Mode, TicketClaims, public_key_bytes, sign, verify,
};
use fs2::FileExt;
use rand::RngCore;
use ring::signature::Ed25519KeyPair;
use rusqlite::{Connection, OpenFlags, OptionalExtension, params};
use serde::{Deserialize, Serialize};

pub const TICKET_LIFETIME_MS: i64 = 30_000;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct RelayConfig {
    pub id: String,
    pub credential_file: String,
    pub first_managed_port: u16,
    pub last_managed_port: u16,
    pub max_capacity: u32,
    pub allow_legacy: bool,
    pub allow_encrypted: bool,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Config {
    pub bind: String,
    pub database: String,
    pub tls_cert_file: String,
    pub tls_key_file: String,
    pub host_credential_file: String,
    pub signing_key_file: String,
    pub key_id: String,
    pub heartbeat_interval_millis: i64,
    pub max_probe_age_millis: i64,
    pub relays: Vec<RelayConfig>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Probe {
    pub relay_id: String,
    pub rtt_millis: u32,
    pub measured_at_epoch_millis: i64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AllocateRequest {
    pub client_instance_id: String,
    pub mode: Mode,
    pub probes: Vec<Probe>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AllocateResponse {
    pub ticket: String,
    pub relay_id: String,
    pub public_port: u16,
    pub expires_at_epoch_millis: i64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct BoundPort {
    pub lease_id: String,
    pub port: u16,
    pub generation: u64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct HeartbeatRequest {
    pub bound_ports: Vec<BoundPort>,
    pub capacity: u32,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct RedeemRequest {
    pub ticket: String,
    pub relay_id: String,
    pub client_instance_id: String,
    pub mode: Mode,
    pub public_port: u16,
    pub generation: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RedeemResponse {
    pub lease_id: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ReleaseRequest {
    pub lease_id: String,
    pub relay_id: String,
    pub public_port: u16,
    pub generation: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    Invalid,
    Unavailable,
    ClockRollback,
    Conflict,
    Storage,
}

impl Error {
    pub fn reason(self) -> &'static str {
        match self {
            Self::Invalid => "invalid",
            Self::Unavailable => "unavailable",
            Self::ClockRollback => "clock_rollback",
            Self::Conflict => "conflict",
            Self::Storage => "storage",
        }
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|t| t.as_millis() as i64)
        .unwrap_or(0)
}

pub struct Coordinator {
    db: Mutex<Connection>,
    _lock: File,
    pub config: Config,
    signing_key: Ed25519KeyPair,
    public_keys: HashMap<String, Vec<u8>>,
    host_digest: [u8; 32],
    relay_digests: HashMap<String, [u8; 32]>,
    metrics: Metrics,
}

#[derive(Default)]
struct Metrics {
    allocations: AtomicU64,
    rejections_invalid: AtomicU64,
    rejections_unavailable: AtomicU64,
    rejections_clock: AtomicU64,
    rejections_conflict: AtomicU64,
    rejections_storage: AtomicU64,
    redemption_failures: AtomicU64,
    allocation_latency_micros: AtomicU64,
    allocation_latency_samples: AtomicU64,
}

fn digest(bytes: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    Sha256::digest(bytes).into()
}
fn credential(path: &str) -> Result<[u8; 32], Error> {
    let content = std::fs::read(path).map_err(|_| Error::Invalid)?;
    let token = content
        .strip_suffix(b"\r\n")
        .or_else(|| content.strip_suffix(b"\n"))
        .unwrap_or(&content);
    if token.len() < 32 || token.len() > 256 || !token.iter().all(|b| b.is_ascii_graphic()) {
        return Err(Error::Invalid);
    }
    Ok(digest(token))
}

impl Coordinator {
    pub fn open(config: Config) -> Result<Self, Error> {
        Self::open_inner(config, false)
    }

    /// Explicitly provision a new database. Refuses any existing path.
    pub fn initialize(config: Config) -> Result<(), Error> {
        let reserved = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&config.database)
            .map_err(|_| Error::Conflict)?;
        drop(reserved);
        drop(Self::open_inner(config, true)?);
        Ok(())
    }

    fn open_inner(config: Config, initialize: bool) -> Result<Self, Error> {
        if config.relays.is_empty()
            || config.heartbeat_interval_millis < 1000
            || config.max_probe_age_millis < 1000
            || config.max_probe_age_millis > 60_000
            || config.key_id.is_empty()
            || config.key_id.len() > 32
        {
            return Err(Error::Invalid);
        }
        let mut ids = HashSet::new();
        let mut relay_digests = HashMap::new();
        for relay in &config.relays {
            if !ids.insert(&relay.id)
                || !relay
                    .id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
                || relay.first_managed_port == 0
                || relay.first_managed_port > relay.last_managed_port
                || relay.max_capacity == 0
                || (!relay.allow_legacy && !relay.allow_encrypted)
            {
                return Err(Error::Invalid);
            }
            relay_digests.insert(relay.id.clone(), credential(&relay.credential_file)?);
        }
        let host_digest = credential(&config.host_credential_file)?;
        if relay_digests.values().any(|value| value == &host_digest)
            || relay_digests.values().collect::<HashSet<_>>().len() != relay_digests.len()
        {
            return Err(Error::Invalid);
        }
        let key_bytes = std::fs::read(&config.signing_key_file).map_err(|_| Error::Invalid)?;
        let signing_key = Ed25519KeyPair::from_pkcs8(&key_bytes).map_err(|_| Error::Invalid)?;
        let public_keys = HashMap::from([(config.key_id.clone(), public_key_bytes(&signing_key))]);
        let lock_path = format!("{}.owner.lock", config.database);
        let lock = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(false)
            .open(lock_path)
            .map_err(|_| Error::Storage)?;
        lock.try_lock_exclusive().map_err(|_| Error::Conflict)?;
        let flags = if initialize {
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE
        } else {
            OpenFlags::SQLITE_OPEN_READ_WRITE
        };
        let db =
            Connection::open_with_flags(&config.database, flags).map_err(|_| Error::Storage)?;
        db.busy_timeout(std::time::Duration::from_secs(10))
            .map_err(|_| Error::Storage)?;
        if !initialize {
            let present: i64 = db
                .query_row(
                    "SELECT count(*) FROM sqlite_master WHERE name IN ('meta','relays','leases','one_active_port')",
                    [],
                    |row| row.get(0),
                )
                .map_err(|_| Error::Storage)?;
            if present != 4 {
                return Err(Error::Storage);
            }
        }
        db.pragma_update(None, "journal_mode", "WAL")
            .map_err(|_| Error::Storage)?;
        db.execute_batch("CREATE TABLE IF NOT EXISTS meta (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS relays (id TEXT PRIMARY KEY, last_heartbeat INTEGER NOT NULL DEFAULT 0, reconciled INTEGER NOT NULL DEFAULT 0, capacity INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS leases (id TEXT PRIMARY KEY, relay_id TEXT NOT NULL, port INTEGER NOT NULL, client_id TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL, issued INTEGER NOT NULL, expires INTEGER NOT NULL, generation INTEGER NOT NULL DEFAULT 0);
            CREATE UNIQUE INDEX IF NOT EXISTS one_active_port ON leases(relay_id,port) WHERE state IN ('reserved','active','quarantined');
            CREATE INDEX IF NOT EXISTS lease_expiry ON leases(state,expires);
            INSERT OR IGNORE INTO meta(name,value) VALUES ('clock_high_water',0);").map_err(|_| Error::Storage)?;
        db.execute("UPDATE relays SET reconciled=0, capacity=0", [])
            .map_err(|_| Error::Storage)?;
        db.execute(
            "UPDATE leases SET state='quarantined' WHERE state='active'",
            [],
        )
        .map_err(|_| Error::Storage)?;
        for relay in &config.relays {
            db.execute("INSERT OR IGNORE INTO relays(id) VALUES (?1)", [&relay.id])
                .map_err(|_| Error::Storage)?;
        }
        Ok(Self {
            db: Mutex::new(db),
            _lock: lock,
            config,
            signing_key,
            public_keys,
            host_digest,
            relay_digests,
            metrics: Metrics::default(),
        })
    }

    pub fn authenticate_host(&self, token: &str) -> bool {
        use subtle::ConstantTimeEq;
        bool::from(self.host_digest.ct_eq(&digest(token.as_bytes())))
    }
    pub fn authenticate_relay(&self, id: &str, token: &str) -> bool {
        use subtle::ConstantTimeEq;
        self.relay_digests
            .get(id)
            .is_some_and(|expected| bool::from(expected.ct_eq(&digest(token.as_bytes()))))
    }
    pub fn public_key_hex(&self) -> String {
        hex::encode(self.public_keys.get(&self.config.key_id).unwrap())
    }

    fn clock(db: &Connection, now: i64) -> Result<(), Error> {
        let high: i64 = db
            .query_row(
                "SELECT value FROM meta WHERE name='clock_high_water'",
                [],
                |row| row.get(0),
            )
            .map_err(|_| Error::Storage)?;
        if now < high {
            return Err(Error::ClockRollback);
        }
        db.execute(
            "UPDATE meta SET value=?1 WHERE name='clock_high_water'",
            [now],
        )
        .map_err(|_| Error::Storage)?;
        Ok(())
    }

    pub fn allocate(&self, request: AllocateRequest, now: i64) -> Result<AllocateResponse, Error> {
        if request.client_instance_id.is_empty()
            || request.client_instance_id.len() > 128
            || !request
                .client_instance_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            || request.probes.is_empty()
            || request.probes.len() > 64
        {
            return Err(Error::Invalid);
        }
        let mut samples: HashMap<&str, Vec<u32>> = HashMap::new();
        for probe in &request.probes {
            if probe.rtt_millis == 0
                || probe.rtt_millis > 60_000
                || probe.measured_at_epoch_millis > now
                || now.saturating_sub(probe.measured_at_epoch_millis)
                    > self.config.max_probe_age_millis
            {
                continue;
            }
            samples
                .entry(&probe.relay_id)
                .or_default()
                .push(probe.rtt_millis);
        }
        let mut ranked = Vec::new();
        for relay in &self.config.relays {
            if (request.mode == Mode::Legacy && !relay.allow_legacy)
                || (request.mode == Mode::Encrypted && !relay.allow_encrypted)
            {
                continue;
            }
            if let Some(values) = samples.get_mut(relay.id.as_str()) {
                values.sort_unstable();
                let median_twice = if values.len() % 2 == 0 {
                    values[values.len() / 2 - 1] as u64 + values[values.len() / 2] as u64
                } else {
                    2 * values[values.len() / 2] as u64
                };
                ranked.push((median_twice, &relay.id, relay));
            }
        }
        ranked.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(b.1)));
        let mut db = self.db.lock().map_err(|_| Error::Storage)?;
        let tx = db.transaction().map_err(|_| Error::Storage)?;
        Self::clock(&tx, now)?;
        tx.execute(
            "UPDATE leases SET state='expired' WHERE state='reserved' AND expires<=?1",
            [now],
        )
        .map_err(|_| Error::Storage)?;
        tx.execute(
            "DELETE FROM leases WHERE state IN ('expired','released') AND expires<?1",
            [now.saturating_sub(86_400_000)],
        )
        .map_err(|_| Error::Storage)?;
        for (_, id, relay) in ranked {
            let (last, reconciled, capacity): (i64, i64, i64) = tx
                .query_row(
                    "SELECT last_heartbeat,reconciled,capacity FROM relays WHERE id=?1",
                    [id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .map_err(|_| Error::Storage)?;
            if reconciled == 0
                || now.saturating_sub(last)
                    > self.config.heartbeat_interval_millis.saturating_mul(2)
                || capacity <= 0
            {
                continue;
            }
            let occupied: i64 = tx.query_row("SELECT count(*) FROM leases WHERE relay_id=?1 AND state IN ('reserved','active','quarantined')", [id], |r| r.get(0)).map_err(|_| Error::Storage)?;
            if occupied >= capacity {
                continue;
            }
            for port in relay.first_managed_port..=relay.last_managed_port {
                let used: bool = tx.query_row("SELECT 1 FROM leases WHERE relay_id=?1 AND port=?2 AND state IN ('reserved','active','quarantined')", params![id,port], |_| Ok(true)).optional().map_err(|_| Error::Storage)?.unwrap_or(false);
                if used {
                    continue;
                }
                let mut random = [0u8; 16];
                rand::rng().fill_bytes(&mut random);
                use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
                let lease_id = URL_SAFE_NO_PAD.encode(random);
                let expires = now + TICKET_LIFETIME_MS;
                tx.execute("INSERT INTO leases(id,relay_id,port,client_id,mode,state,issued,expires) VALUES (?1,?2,?3,?4,?5,'reserved',?6,?7)", params![lease_id,id,port,request.client_instance_id,mode_text(request.mode),now,expires]).map_err(|_| Error::Storage)?;
                let claims = TicketClaims {
                    version: 1,
                    lease_id,
                    key_id: self.config.key_id.clone(),
                    relay_id: id.clone(),
                    public_port: port,
                    client_instance_id: request.client_instance_id,
                    mode: request.mode,
                    issued_at_epoch_millis: now,
                    expires_at_epoch_millis: expires,
                };
                let ticket = sign(&claims, &self.signing_key).map_err(|_| Error::Invalid)?;
                tx.commit().map_err(|_| Error::Storage)?;
                return Ok(AllocateResponse {
                    ticket,
                    relay_id: id.clone(),
                    public_port: port,
                    expires_at_epoch_millis: expires,
                });
            }
        }
        tx.commit().map_err(|_| Error::Storage)?;
        Err(Error::Unavailable)
    }

    pub fn redeem(&self, req: RedeemRequest, now: i64) -> Result<RedeemResponse, Error> {
        if req.generation == 0 || req.generation > i64::MAX as u64 {
            return Err(Error::Invalid);
        }
        let claims = verify(
            &req.ticket,
            &self.public_keys,
            Expected {
                relay_id: &req.relay_id,
                public_port: req.public_port,
                client_instance_id: &req.client_instance_id,
                mode: req.mode,
            },
            now,
        )
        .map_err(|_| Error::Invalid)?;
        let mut db = self.db.lock().map_err(|_| Error::Storage)?;
        let tx = db.transaction().map_err(|_| Error::Storage)?;
        Self::clock(&tx, now)?;
        let healthy: bool = tx.query_row("SELECT reconciled=1 AND ?2>=last_heartbeat AND ?2-last_heartbeat<=?3 FROM relays WHERE id=?1", params![req.relay_id,now,self.config.heartbeat_interval_millis.saturating_mul(2)], |r| r.get(0)).optional().map_err(|_| Error::Storage)?.unwrap_or(false);
        if !healthy {
            return Err(Error::Unavailable);
        }
        let changed = tx.execute("UPDATE leases SET state='active',generation=?1 WHERE id=?2 AND relay_id=?3 AND port=?4 AND client_id=?5 AND mode=?6 AND state='reserved' AND issued=?7 AND expires=?8 AND expires>?9", params![req.generation as i64,claims.lease_id,req.relay_id,req.public_port,req.client_instance_id,mode_text(req.mode),claims.issued_at_epoch_millis,claims.expires_at_epoch_millis,now]).map_err(|_| Error::Storage)?;
        if changed != 1 {
            return Err(Error::Conflict);
        }
        tx.commit().map_err(|_| Error::Storage)?;
        Ok(RedeemResponse {
            lease_id: claims.lease_id,
        })
    }

    pub fn heartbeat(&self, relay_id: &str, req: HeartbeatRequest, now: i64) -> Result<(), Error> {
        let relay = self
            .config
            .relays
            .iter()
            .find(|r| r.id == relay_id)
            .ok_or(Error::Invalid)?;
        if req.capacity > relay.max_capacity || req.bound_ports.len() > relay.max_capacity as usize
        {
            return Err(Error::Invalid);
        }
        let mut seen = HashSet::new();
        for bound in &req.bound_ports {
            if bound.port < relay.first_managed_port
                || bound.port > relay.last_managed_port
                || bound.generation == 0
                || bound.generation > i64::MAX as u64
                || !seen.insert(bound.port)
            {
                return Err(Error::Invalid);
            }
        }
        let mut db = self.db.lock().map_err(|_| Error::Storage)?;
        let tx = db.transaction().map_err(|_| Error::Storage)?;
        Self::clock(&tx, now)?;
        for bound in &req.bound_ports {
            let match_count: i64 = tx.query_row("SELECT count(*) FROM leases WHERE id=?1 AND relay_id=?2 AND port=?3 AND generation<=?4 AND state IN ('active','quarantined')", params![bound.lease_id,relay_id,bound.port,bound.generation as i64], |r| r.get(0)).map_err(|_| Error::Storage)?;
            if match_count != 1 {
                return Err(Error::Conflict);
            }
        }
        // Relay registration and heartbeat share a lock through redemption and bind.
        // Its full authenticated inventory proves omitted listeners are gone.
        tx.execute(
            "UPDATE leases SET state='quarantined' WHERE relay_id=?1 AND state='active'",
            [relay_id],
        )
        .map_err(|_| Error::Storage)?;
        for bound in &req.bound_ports {
            tx.execute("UPDATE leases SET state='active',generation=?2 WHERE id=?1 AND state='quarantined'", params![bound.lease_id,bound.generation as i64]).map_err(|_| Error::Storage)?;
        }
        tx.execute(
            "UPDATE leases SET state='released' WHERE relay_id=?1 AND state='quarantined'",
            [relay_id],
        )
        .map_err(|_| Error::Storage)?;
        tx.execute(
            "UPDATE relays SET last_heartbeat=?2,reconciled=1,capacity=?3 WHERE id=?1",
            params![relay_id, now, req.capacity],
        )
        .map_err(|_| Error::Storage)?;
        tx.commit().map_err(|_| Error::Storage)?;
        Ok(())
    }

    pub fn release(&self, req: ReleaseRequest, now: i64) -> Result<(), Error> {
        if req.generation == 0 || req.generation > i64::MAX as u64 {
            return Err(Error::Invalid);
        }
        let mut db = self.db.lock().map_err(|_| Error::Storage)?;
        let tx = db.transaction().map_err(|_| Error::Storage)?;
        Self::clock(&tx, now)?;
        let state: Option<String> = tx.query_row("SELECT state FROM leases WHERE id=?1 AND relay_id=?2 AND port=?3 AND generation=?4", params![req.lease_id,req.relay_id,req.public_port,req.generation as i64], |r| r.get(0)).optional().map_err(|_| Error::Storage)?;
        match state.as_deref() {
            Some("active" | "quarantined" | "released") => {}
            _ => return Err(Error::Conflict),
        }
        tx.execute(
            "UPDATE leases SET state='released' WHERE id=?1",
            [&req.lease_id],
        )
        .map_err(|_| Error::Storage)?;
        tx.commit().map_err(|_| Error::Storage)?;
        Ok(())
    }

    pub fn counts(&self) -> Result<(i64, i64, i64), Error> {
        let db = self.db.lock().map_err(|_| Error::Storage)?;
        let active = db
            .query_row(
                "SELECT count(*) FROM leases WHERE state='active'",
                [],
                |r| r.get(0),
            )
            .map_err(|_| Error::Storage)?;
        let quarantined = db
            .query_row(
                "SELECT count(*) FROM leases WHERE state='quarantined'",
                [],
                |r| r.get(0),
            )
            .map_err(|_| Error::Storage)?;
        let expired = db
            .query_row(
                "SELECT count(*) FROM leases WHERE state='expired'",
                [],
                |r| r.get(0),
            )
            .map_err(|_| Error::Storage)?;
        Ok((active, quarantined, expired))
    }

    pub fn record_allocation(&self, result: Result<(), Error>, latency_micros: u64) {
        self.metrics
            .allocation_latency_micros
            .fetch_add(latency_micros, Ordering::Relaxed);
        self.metrics
            .allocation_latency_samples
            .fetch_add(1, Ordering::Relaxed);
        match result {
            Ok(()) => &self.metrics.allocations,
            Err(Error::Invalid) => &self.metrics.rejections_invalid,
            Err(Error::Unavailable) => &self.metrics.rejections_unavailable,
            Err(Error::ClockRollback) => &self.metrics.rejections_clock,
            Err(Error::Conflict) => &self.metrics.rejections_conflict,
            Err(Error::Storage) => &self.metrics.rejections_storage,
        }
        .fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_redemption_failure(&self) {
        self.metrics
            .redemption_failures
            .fetch_add(1, Ordering::Relaxed);
    }

    pub fn metrics_text(&self, now: i64) -> Result<String, Error> {
        let (active, quarantined, expired) = self.counts()?;
        let db = self.db.lock().map_err(|_| Error::Storage)?;
        let healthy:i64=db.query_row("SELECT count(*) FROM relays WHERE reconciled=1 AND last_heartbeat<=?1 AND ?1-last_heartbeat<=?2 AND capacity>0", params![now,self.config.heartbeat_interval_millis.saturating_mul(2)], |r|r.get(0)).map_err(|_| Error::Storage)?;
        let n = |counter: &AtomicU64| counter.load(Ordering::Relaxed);
        Ok(format!(
            "bta_coordinator_healthy_relays {healthy}\nbta_coordinator_active_leases {active}\nbta_coordinator_quarantined_leases {quarantined}\nbta_coordinator_expired_leases {expired}\nbta_coordinator_allocations_total {}\nbta_coordinator_allocation_rejections_invalid_total {}\nbta_coordinator_allocation_rejections_unavailable_total {}\nbta_coordinator_allocation_rejections_clock_total {}\nbta_coordinator_allocation_rejections_conflict_total {}\nbta_coordinator_allocation_rejections_storage_total {}\nbta_coordinator_redemption_failures_total {}\nbta_coordinator_allocation_latency_micros_total {}\nbta_coordinator_allocation_latency_samples_total {}\n",
            n(&self.metrics.allocations),
            n(&self.metrics.rejections_invalid),
            n(&self.metrics.rejections_unavailable),
            n(&self.metrics.rejections_clock),
            n(&self.metrics.rejections_conflict),
            n(&self.metrics.rejections_storage),
            n(&self.metrics.redemption_failures),
            n(&self.metrics.allocation_latency_micros),
            n(&self.metrics.allocation_latency_samples)
        ))
    }
}

fn mode_text(mode: Mode) -> &'static str {
    match mode {
        Mode::Legacy => "legacy",
        Mode::Encrypted => "encrypted",
    }
}

pub fn load_config(path: impl AsRef<Path>) -> Result<Config, anyhow::Error> {
    Ok(toml::from_str(&std::fs::read_to_string(path)?)?)
}
