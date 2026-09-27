use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Arc,
};

use anyhow::{Context, Result};
use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, Path as AxumPath, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
};
use bta_anywhere_coordinator::{
    AllocateRequest, Config, Coordinator, Error, HeartbeatRequest, RedeemRequest, RelayConfig,
    ReleaseRequest, load_config, now_ms,
};
use clap::{Parser, Subcommand};
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use rand::RngCore;
use rcgen::{CertificateParams, DnType, ExtendedKeyUsagePurpose, KeyPair, KeyUsagePurpose};
use ring::{
    rand::SystemRandom,
    signature::{Ed25519KeyPair, KeyPair as _},
};
use rusqlite::{Connection, OpenFlags};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use serde_json::json;
use tokio_rustls::TlsAcceptor;
use tower::Service;

#[derive(Parser)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}
#[derive(Subcommand)]
enum Command {
    Serve {
        #[arg(long)]
        config: PathBuf,
    },
    InitDev {
        #[arg(long)]
        dir: PathBuf,
    },
    InitDb {
        #[arg(long)]
        config: PathBuf,
    },
    PublicKey {
        #[arg(long)]
        config: PathBuf,
    },
    Backup {
        #[arg(long)]
        config: PathBuf,
        #[arg(long)]
        output: PathBuf,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt().with_env_filter("info").init();
    match Cli::parse().command {
        Command::Serve { config } => serve(config).await,
        Command::InitDev { dir } => init_dev(&dir),
        Command::InitDb { config } => init_db(&config),
        Command::PublicKey { config } => public_key(&config),
        Command::Backup { config, output } => backup(&config, &output),
    }
}

fn bearer(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(axum::http::header::AUTHORIZATION)?
        .to_str()
        .ok()?
        .strip_prefix("Bearer ")
}
fn err(e: Error) -> impl IntoResponse {
    let status = match e {
        Error::Invalid => StatusCode::BAD_REQUEST,
        Error::Unavailable => StatusCode::SERVICE_UNAVAILABLE,
        Error::ClockRollback => StatusCode::SERVICE_UNAVAILABLE,
        Error::Conflict => StatusCode::CONFLICT,
        Error::Storage => StatusCode::INTERNAL_SERVER_ERROR,
    };
    (status, Json(json!({"error": e.reason()})))
}
fn unauthorized() -> impl IntoResponse {
    (
        StatusCode::UNAUTHORIZED,
        Json(json!({"error":"unauthorized"})),
    )
}

async fn allocate(
    State(coordinator): State<Arc<Coordinator>>,
    headers: HeaderMap,
    Json(request): Json<AllocateRequest>,
) -> impl IntoResponse {
    if !bearer(&headers).is_some_and(|t| coordinator.authenticate_host(t)) {
        return unauthorized().into_response();
    }
    let started = std::time::Instant::now();
    let result = coordinator.allocate(request, now_ms());
    coordinator.record_allocation(
        result.as_ref().map(|_| ()).map_err(|e| *e),
        started.elapsed().as_micros().min(u64::MAX as u128) as u64,
    );
    match result {
        Ok(response) => (StatusCode::OK, Json(json!(response))).into_response(),
        Err(e) => err(e).into_response(),
    }
}
async fn heartbeat(
    State(coordinator): State<Arc<Coordinator>>,
    AxumPath(id): AxumPath<String>,
    headers: HeaderMap,
    Json(request): Json<HeartbeatRequest>,
) -> impl IntoResponse {
    if !bearer(&headers).is_some_and(|t| coordinator.authenticate_relay(&id, t)) {
        return unauthorized().into_response();
    }
    match coordinator.heartbeat(&id, request, now_ms()) {
        Ok(()) => (StatusCode::OK, Json(json!({"healthy":true}))).into_response(),
        Err(e) => err(e).into_response(),
    }
}
async fn redeem(
    State(coordinator): State<Arc<Coordinator>>,
    headers: HeaderMap,
    Json(request): Json<RedeemRequest>,
) -> impl IntoResponse {
    if !bearer(&headers).is_some_and(|t| coordinator.authenticate_relay(&request.relay_id, t)) {
        return unauthorized().into_response();
    }
    let result = coordinator.redeem(request, now_ms());
    if result.is_err() {
        coordinator.record_redemption_failure();
    }
    match result {
        Ok(response) => (StatusCode::OK, Json(json!(response))).into_response(),
        Err(e) => err(e).into_response(),
    }
}
async fn release(
    State(coordinator): State<Arc<Coordinator>>,
    headers: HeaderMap,
    Json(request): Json<ReleaseRequest>,
) -> impl IntoResponse {
    if !bearer(&headers).is_some_and(|t| coordinator.authenticate_relay(&request.relay_id, t)) {
        return unauthorized().into_response();
    }
    match coordinator.release(request, now_ms()) {
        Ok(()) => (StatusCode::OK, Json(json!({"released":true}))).into_response(),
        Err(e) => err(e).into_response(),
    }
}
async fn metrics(State(coordinator): State<Arc<Coordinator>>) -> impl IntoResponse {
    match coordinator.metrics_text(now_ms()) {
        Ok(body) => (StatusCode::OK, body).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn serve(path: PathBuf) -> Result<()> {
    let config: Config = load_config(path)?;
    let certs: Vec<CertificateDer<'static>> =
        rustls_pemfile::certs(&mut &*fs::read(&config.tls_cert_file)?)
            .collect::<std::result::Result<_, _>>()?;
    let key: PrivateKeyDer<'static> =
        rustls_pemfile::private_key(&mut &*fs::read(&config.tls_key_file)?)?
            .context("missing TLS key")?;
    let tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key)?;
    let coordinator =
        Arc::new(Coordinator::open(config.clone()).map_err(|e| anyhow::anyhow!(e.reason()))?);
    let app = Router::new()
        .route("/v1/allocate", post(allocate))
        .route("/v1/relays/{id}/heartbeat", post(heartbeat))
        .route("/v1/leases/redeem", post(redeem))
        .route("/v1/leases/release", post(release))
        .route("/metrics", get(metrics))
        .layer(DefaultBodyLimit::max(64 * 1024))
        .with_state(coordinator);
    let listener = tokio::net::TcpListener::bind(&config.bind).await?;
    let acceptor = TlsAcceptor::from(Arc::new(tls));
    tracing::info!(bind=%config.bind, "coordinator listening");
    loop {
        let (socket, _) = listener.accept().await?;
        let acceptor = acceptor.clone();
        let app = app.clone();
        tokio::spawn(async move {
            let Ok(tls) = acceptor.accept(socket).await else {
                return;
            };
            let service = service_fn(move |request: hyper::Request<hyper::body::Incoming>| {
                let mut app = app.clone();
                async move { app.call(request.map(Body::new)).await }
            });
            let _ = hyper::server::conn::http1::Builder::new()
                .serve_connection(TokioIo::new(tls), service)
                .await;
        });
    }
}

fn write_new(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut f = OpenOptions::new().write(true).create_new(true).open(path)?;
    f.write_all(bytes)?;
    Ok(())
}
fn init_dev(dir: &Path) -> Result<()> {
    fs::create_dir(dir).with_context(|| format!("create {}", dir.display()))?;
    let mut params = CertificateParams::new(vec!["localhost".into(), "127.0.0.1".into()])?;
    params
        .distinguished_name
        .push(DnType::CommonName, "localhost");
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    let tls_key = KeyPair::generate()?;
    let certificate = params.self_signed(&tls_key)?;
    let signing = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new())
        .map_err(|_| anyhow::anyhow!("signing key generation failed"))?;
    let mut token = [0u8; 32];
    rand::rng().fill_bytes(&mut token);
    let host = hex::encode(token);
    rand::rng().fill_bytes(&mut token);
    let relay = hex::encode(token);
    write_new(&dir.join("tls-cert.pem"), certificate.pem().as_bytes())?;
    write_new(&dir.join("tls-key.pem"), tls_key.serialize_pem().as_bytes())?;
    write_new(&dir.join("signing-key.pk8"), signing.as_ref())?;
    write_new(&dir.join("host.token"), host.as_bytes())?;
    write_new(&dir.join("relay.token"), relay.as_bytes())?;
    let abs = fs::canonicalize(dir)?;
    let config = Config {
        bind: "127.0.0.1:30450".into(),
        database: abs.join("coordinator.sqlite").display().to_string(),
        tls_cert_file: abs.join("tls-cert.pem").display().to_string(),
        tls_key_file: abs.join("tls-key.pem").display().to_string(),
        host_credential_file: abs.join("host.token").display().to_string(),
        signing_key_file: abs.join("signing-key.pk8").display().to_string(),
        key_id: "dev-key-1".into(),
        heartbeat_interval_millis: 5000,
        max_probe_age_millis: 10000,
        relays: vec![RelayConfig {
            id: "dev-relay-1".into(),
            credential_file: abs.join("relay.token").display().to_string(),
            first_managed_port: 30500,
            last_managed_port: 30599,
            max_capacity: 100,
            allow_legacy: true,
            allow_encrypted: true,
        }],
    };
    write_new(
        &dir.join("coordinator.toml"),
        toml::to_string_pretty(&config)?.as_bytes(),
    )?;
    Coordinator::initialize(config).map_err(|e| anyhow::anyhow!(e.reason()))?;
    println!("Development coordinator files written to {}", abs.display());
    println!(
        "TLS trust certificate: {}",
        abs.join("tls-cert.pem").display()
    );
    println!(
        "Ticket verification key (hex): {}",
        hex::encode(
            ring::signature::Ed25519KeyPair::from_pkcs8(signing.as_ref())
                .map_err(|_| anyhow::anyhow!("key parse failed"))?
                .public_key()
                .as_ref()
        )
    );
    Ok(())
}

fn init_db(config_path: &Path) -> Result<()> {
    let config = load_config(config_path)?;
    let path = config.database.clone();
    Coordinator::initialize(config).map_err(|e| anyhow::anyhow!(e.reason()))?;
    println!("Initialized coordinator database: {path}");
    Ok(())
}

fn public_key(config_path: &Path) -> Result<()> {
    let config = load_config(config_path)?;
    let pkcs8 = fs::read(&config.signing_key_file)?;
    let key =
        Ed25519KeyPair::from_pkcs8(&pkcs8).map_err(|_| anyhow::anyhow!("invalid signing key"))?;
    use base64::{Engine as _, engine::general_purpose::STANDARD};
    println!(
        "{} {}",
        config.key_id,
        STANDARD.encode(key.public_key().as_ref())
    );
    Ok(())
}

fn backup(config_path: &Path, output: &Path) -> Result<()> {
    let config = load_config(config_path)?;
    // create_new makes accidental backup overwrite impossible, including a concurrent run.
    let reserved = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(output)?;
    drop(reserved);
    let source = Connection::open_with_flags(&config.database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    source.backup("main", output, None)?;
    let check = Connection::open_with_flags(output, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let integrity: String = check.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
    anyhow::ensure!(integrity == "ok", "backup integrity check failed");
    println!("Consistent SQLite backup: {}", output.display());
    Ok(())
}
