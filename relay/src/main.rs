mod config;
mod metrics;
mod protocol;
mod state;
mod token;

use std::{
    io::BufRead,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use anyhow::{Context, Result, anyhow, bail};
use axum::{
    Router,
    extract::State,
    http::{StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use clap::{Parser, Subcommand};
use protocol::{ALPN, ClientControl, PROTOCOL_VERSION, ServerControl, read_json, write_json};
use quinn::{Endpoint, VarInt, crypto::rustls::QuicServerConfig};
use rcgen::{
    BasicConstraints, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair,
    KeyUsagePurpose,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use state::{Registration, RelayState};
use tokio::{io::AsyncWriteExt, net::TcpListener, time};
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

use crate::config::RelayConfig;

#[derive(Debug, Parser)]
#[command(name = "bta-anywhere-relay", version, about)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Run the relay using a TOML configuration file.
    Run {
        #[arg(long, default_value = "relay.toml")]
        config: PathBuf,
    },
    /// Create a localhost certificate, token, and development configuration.
    InitDev {
        #[arg(long, default_value = ".dev/relay")]
        output: PathBuf,
    },
    /// Create a random access token file and print only its SHA-256 configuration value.
    GenerateToken {
        #[arg(long)]
        output: PathBuf,
    },
    /// Print the SHA-256 value for a token file, or a token read from standard input.
    HashToken {
        #[arg(long)]
        token_file: Option<PathBuf>,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .with_target(false)
        .compact()
        .init();

    match Cli::parse().command {
        Command::Run { config } => run(config).await,
        Command::InitDev { output } => init_dev(&output).await,
        Command::GenerateToken { output } => generate_token(&output).await,
        Command::HashToken { token_file } => {
            let token = read_token(token_file.as_deref())?;
            println!("{}", token::hash_hex(&token));
            Ok(())
        }
    }
}

async fn generate_token(output: &Path) -> Result<()> {
    if output.exists() {
        bail!(
            "refusing to replace existing token file {}",
            output.display()
        );
    }
    if let Some(parent) = output
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        tokio::fs::create_dir_all(parent).await?;
    }
    let access_token = token::generate();
    write_private(output, format!("{access_token}\n").as_bytes()).await?;
    println!("Token written to {}", output.display());
    println!(
        "access_token_hashes entry: {}",
        token::hash_hex(&access_token)
    );
    Ok(())
}

fn read_token(path: Option<&Path>) -> Result<String> {
    let raw = if let Some(path) = path {
        std::fs::read_to_string(path)
            .with_context(|| format!("failed to read token file {}", path.display()))?
    } else {
        let mut line = String::new();
        std::io::stdin()
            .lock()
            .read_line(&mut line)
            .context("failed to read access token from standard input")?;
        line
    };
    let access_token = raw.trim_end_matches(['\r', '\n']);
    if access_token.is_empty() {
        bail!("access token cannot be empty");
    }
    Ok(access_token.to_owned())
}

async fn run(config_path: PathBuf) -> Result<()> {
    let config = RelayConfig::load(&config_path).await?;
    let server_config = load_server_config(&config).await?;
    let endpoint = Endpoint::server(server_config, config.quic_listen)
        .with_context(|| format!("failed to bind QUIC endpoint at {}", config.quic_listen))?;
    let state = RelayState::new(config.clone())?;
    let admin_listener = TcpListener::bind(config.admin_listen)
        .await
        .with_context(|| format!("failed to bind admin endpoint at {}", config.admin_listen))?;
    let admin_state = state.clone();
    let admin_shutdown = state.shutdown_token();
    let internal_shutdown = state.shutdown_token();
    let admin = tokio::spawn(async move {
        if let Err(error) = serve_admin(admin_state.clone(), admin_shutdown, admin_listener).await {
            error!(%error, "admin server stopped unexpectedly");
            admin_state.shutdown();
        }
    });

    state.set_ready(true);
    info!(quic = %config.quic_listen, tcp_start = config.tcp_port_start, tcp_end = config.tcp_port_end, "relay ready");

    let requested_shutdown = shutdown_signal();
    tokio::pin!(requested_shutdown);
    loop {
        tokio::select! {
            _ = &mut requested_shutdown => {
                info!("shutdown requested");
                break;
            }
            _ = internal_shutdown.cancelled() => {
                warn!("internal relay shutdown requested");
                break;
            }
            incoming = endpoint.accept() => {
                let Some(incoming) = incoming else { break; };
                let connection_state = state.clone();
                tokio::spawn(async move {
                    match incoming.await {
                        Ok(connection) => {
                            if let Err(error) = handle_connection(connection_state, connection).await {
                                warn!(%error, "host control connection rejected or closed");
                            }
                        }
                        Err(error) => warn!(%error, "QUIC handshake failed"),
                    }
                });
            }
        }
    }

    state.shutdown();
    endpoint.close(VarInt::from_u32(0), b"relay shutdown");
    state.close_all().await;
    let _ = time::timeout(Duration::from_secs(5), endpoint.wait_idle()).await;
    let _ = admin.await;
    Ok(())
}

async fn handle_connection(state: RelayState, connection: quinn::Connection) -> Result<()> {
    let remote = connection.remote_address();
    let (mut send, mut receive) = time::timeout(Duration::from_secs(10), connection.accept_bi())
        .await
        .context("timed out waiting for registration stream")??;
    let registration = match time::timeout(
        Duration::from_secs(10),
        read_json::<_, ClientControl>(&mut receive),
    )
    .await
    {
        Ok(Ok(ClientControl::Register {
            version,
            access_token,
            client_instance_id,
            resume_token,
        })) => {
            if version != PROTOCOL_VERSION {
                send_error(
                    &mut send,
                    "unsupported_version",
                    "unsupported protocol version",
                    false,
                )
                .await?;
                bail!("unsupported protocol version {version}");
            }
            let Some(access_hash) = state.authenticate(&access_token) else {
                state.metrics().rejected_total.inc();
                send_error(
                    &mut send,
                    "authentication_failed",
                    "access token was not accepted",
                    false,
                )
                .await?;
                bail!("authentication failed for {remote}");
            };
            let attempted_resume = resume_token.is_some();
            match state
                .register(
                    access_hash,
                    remote.ip(),
                    client_instance_id,
                    resume_token,
                    connection.clone(),
                )
                .await
            {
                Ok(registration) => registration,
                Err(error) => {
                    state.metrics().rejected_total.inc();
                    let code = if attempted_resume {
                        "resume_rejected"
                    } else {
                        "registration_rejected"
                    };
                    send_error(&mut send, code, &error.to_string(), true).await?;
                    return Err(error);
                }
            }
        }
        Ok(Ok(_)) => {
            send_error(
                &mut send,
                "registration_required",
                "first control message must be register",
                false,
            )
            .await?;
            bail!("first control message was not registration");
        }
        Ok(Err(error)) => return Err(error.into()),
        Err(_) => bail!("timed out reading registration"),
    };

    if let Err(error) = write_registration(&state, &mut send, &registration).await {
        state
            .detach(registration.session.clone(), registration.generation, true)
            .await;
        connection.close(VarInt::from_u32(0), b"registration response failed");
        return Err(error);
    }
    info!(port = registration.session.public_port, resumed = registration.resumed, remote = %remote, "host control stream active");

    let mut immediate = false;
    let control_result = loop {
        match time::timeout(
            Duration::from_secs(state.config().lease_seconds),
            read_json::<_, ClientControl>(&mut receive),
        )
        .await
        {
            Ok(Ok(ClientControl::Ping { sequence })) => {
                if let Err(error) = write_json(&mut send, &ServerControl::Pong { sequence }).await {
                    break Err(error.into());
                }
            }
            Ok(Ok(ClientControl::Close { reason })) => {
                info!(
                    port = registration.session.public_port,
                    reason_length = reason.len(),
                    "host closed session"
                );
                immediate = true;
                break Ok(());
            }
            Ok(Ok(ClientControl::Register { .. })) => {
                immediate = true;
                let result = send_error(
                    &mut send,
                    "already_registered",
                    "control stream is already registered",
                    false,
                )
                .await;
                break result;
            }
            Ok(Err(error)) => {
                warn!(port = registration.session.public_port, %error, "control stream ended");
                break Err(error.into());
            }
            Err(_) => {
                warn!(
                    port = registration.session.public_port,
                    "control lease expired"
                );
                break Ok(());
            }
        }
    };

    state
        .detach(
            registration.session.clone(),
            registration.generation,
            immediate,
        )
        .await;
    connection.close(VarInt::from_u32(0), b"control stream closed");
    control_result
}

async fn write_registration(
    state: &RelayState,
    send: &mut quinn::SendStream,
    registration: &Registration,
) -> Result<()> {
    write_json(
        send,
        &ServerControl::Registered {
            session_id: &registration.session.id,
            public_host: &state.config().public_host,
            public_port: registration.session.public_port,
            resume_token: &registration.resume_token,
            lease_seconds: state.config().lease_seconds,
        },
    )
    .await?;
    Ok(())
}

async fn send_error(
    send: &mut quinn::SendStream,
    code: &str,
    message: &str,
    retryable: bool,
) -> Result<()> {
    write_json(
        send,
        &ServerControl::Error {
            code,
            message,
            retryable,
        },
    )
    .await?;
    send.finish()?;
    let _ = time::timeout(Duration::from_secs(2), send.stopped()).await;
    Ok(())
}

async fn serve_admin(
    state: RelayState,
    shutdown: tokio_util::sync::CancellationToken,
    listener: TcpListener,
) -> Result<()> {
    let address = state.config().admin_listen;
    let router = Router::new()
        .route("/healthz", get(|| async { "ok\n" }))
        .route("/readyz", get(ready))
        .route("/metrics", get(metrics))
        .with_state(state);
    info!(%address, "admin endpoint listening");
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown.cancelled_owned())
        .await?;
    Ok(())
}

async fn shutdown_signal() -> Result<()> {
    #[cfg(unix)]
    {
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        tokio::select! {
            result = tokio::signal::ctrl_c() => result?,
            _ = terminate.recv() => {},
        }
    }
    #[cfg(not(unix))]
    tokio::signal::ctrl_c().await?;
    Ok(())
}

async fn ready(State(state): State<RelayState>) -> impl IntoResponse {
    if state.is_ready() {
        (StatusCode::OK, "ready\n")
    } else {
        (StatusCode::SERVICE_UNAVAILABLE, "not ready\n")
    }
}

async fn metrics(State(state): State<RelayState>) -> Response {
    match state.metrics().encode() {
        Ok(body) => (
            StatusCode::OK,
            [(
                header::CONTENT_TYPE,
                "application/openmetrics-text; version=1.0.0",
            )],
            body,
        )
            .into_response(),
        Err(_) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "metrics encoding failed\n",
        )
            .into_response(),
    }
}

async fn load_server_config(config: &RelayConfig) -> Result<quinn::ServerConfig> {
    let certificate_bytes = tokio::fs::read(&config.certificate_path)
        .await
        .with_context(|| format!("failed to read {}", config.certificate_path.display()))?;
    let key_bytes = tokio::fs::read(&config.private_key_path)
        .await
        .with_context(|| format!("failed to read {}", config.private_key_path.display()))?;
    let certificates = rustls_pemfile::certs(&mut certificate_bytes.as_slice())
        .collect::<Result<Vec<CertificateDer<'static>>, _>>()?;
    let private_key: PrivateKeyDer<'static> =
        rustls_pemfile::private_key(&mut key_bytes.as_slice())?
            .ok_or_else(|| anyhow!("private key file contains no supported key"))?;
    let mut tls = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certificates, private_key)?;
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let crypto = QuicServerConfig::try_from(tls)?;
    let mut server = quinn::ServerConfig::with_crypto(Arc::new(crypto));
    let mut transport = quinn::TransportConfig::default();
    transport.max_concurrent_bidi_streams(VarInt::from_u32(64));
    transport.keep_alive_interval(Some(Duration::from_secs(config.heartbeat_seconds)));
    server.transport_config(Arc::new(transport));
    Ok(server)
}

async fn init_dev(output: &Path) -> Result<()> {
    if output.exists() && output.read_dir()?.next().is_some() {
        bail!("{} already exists and is not empty", output.display());
    }
    tokio::fs::create_dir_all(output).await?;
    let mut ca_params = CertificateParams::new(Vec::new())?;
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    ca_params
        .distinguished_name
        .push(DnType::CommonName, "BTA Anywhere development CA");
    ca_params.key_usages = vec![
        KeyUsagePurpose::DigitalSignature,
        KeyUsagePurpose::KeyCertSign,
        KeyUsagePurpose::CrlSign,
    ];
    let ca_key = KeyPair::generate()?;
    let ca_certificate = ca_params.self_signed(&ca_key)?;
    let issuer = Issuer::new(ca_params, ca_key);

    let mut server_params = CertificateParams::new(vec!["localhost".into(), "127.0.0.1".into()])?;
    server_params
        .distinguished_name
        .push(DnType::CommonName, "localhost");
    server_params.use_authority_key_identifier_extension = true;
    server_params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    server_params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    let server_key = KeyPair::generate()?;
    let server_certificate = server_params.signed_by(&server_key, &issuer)?;
    let certificate_pem = format!("{}{}", server_certificate.pem(), ca_certificate.pem());
    let key_pem = server_key.serialize_pem();
    let token = token::generate();
    let config = RelayConfig {
        certificate_path: "server.pem".into(),
        private_key_path: "server-key.pem".into(),
        access_token_hashes: vec![token::hash_hex(&token)],
        ..RelayConfig::default()
    };
    config.validate()?;

    tokio::fs::write(output.join("server.pem"), &certificate_pem).await?;
    tokio::fs::write(output.join("ca.pem"), ca_certificate.pem()).await?;
    write_private(
        &output.join("ca-key.pem"),
        issuer.key().serialize_pem().as_bytes(),
    )
    .await?;
    write_private(&output.join("server-key.pem"), key_pem.as_bytes()).await?;
    write_private(
        &output.join("access.token"),
        format!("{token}\n").as_bytes(),
    )
    .await?;
    tokio::fs::write(output.join("relay.toml"), toml::to_string_pretty(&config)?).await?;

    println!("Development relay files written to {}", output.display());
    println!("Trust certificate: {}", output.join("ca.pem").display());
    println!("Access token: {}", output.join("access.token").display());
    println!(
        "Run: bta-anywhere-relay run --config {}",
        output.join("relay.toml").display()
    );
    Ok(())
}

async fn write_private(path: &Path, contents: &[u8]) -> Result<()> {
    let mut options = tokio::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        options.mode(0o600);
    }
    let mut file = options
        .open(path)
        .await
        .with_context(|| format!("failed to create private file {}", path.display()))?;
    file.write_all(contents).await?;
    file.flush().await?;
    Ok(())
}
