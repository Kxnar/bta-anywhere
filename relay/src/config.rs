use std::{
    net::{IpAddr, SocketAddr},
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default)]
pub struct RelayConfig {
    pub quic_listen: SocketAddr,
    pub tcp_bind_ip: IpAddr,
    pub public_host: String,
    pub tcp_port_start: u16,
    pub tcp_port_end: u16,
    pub admin_listen: SocketAddr,
    pub certificate_path: PathBuf,
    pub private_key_path: PathBuf,
    pub access_token_hashes: Vec<String>,
    pub max_sessions_per_token: usize,
    pub max_sessions_per_ip: usize,
    pub max_connections_per_session: usize,
    pub max_accepts_per_minute: u32,
    pub heartbeat_seconds: u64,
    pub lease_seconds: u64,
    pub resume_grace_seconds: u64,
}

impl Default for RelayConfig {
    fn default() -> Self {
        Self {
            quic_listen: "0.0.0.0:25575".parse().expect("valid address"),
            tcp_bind_ip: "0.0.0.0".parse().expect("valid address"),
            public_host: "localhost".into(),
            tcp_port_start: 30_000,
            tcp_port_end: 30_100,
            admin_listen: "127.0.0.1:9090".parse().expect("valid address"),
            certificate_path: "server.pem".into(),
            private_key_path: "server-key.pem".into(),
            access_token_hashes: Vec::new(),
            max_sessions_per_token: 2,
            max_sessions_per_ip: 3,
            max_connections_per_session: 8,
            max_accepts_per_minute: 30,
            heartbeat_seconds: 15,
            lease_seconds: 90,
            resume_grace_seconds: 60,
        }
    }
}

impl RelayConfig {
    pub async fn load(path: &Path) -> Result<Self> {
        let raw = tokio::fs::read_to_string(path)
            .await
            .with_context(|| format!("failed to read {}", path.display()))?;
        let mut config: Self =
            toml::from_str(&raw).with_context(|| format!("failed to parse {}", path.display()))?;
        let base = path.parent().unwrap_or_else(|| Path::new("."));
        config.certificate_path = resolve(base, &config.certificate_path);
        config.private_key_path = resolve(base, &config.private_key_path);
        config.validate()?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<()> {
        if self.public_host.trim().is_empty() {
            bail!("public_host cannot be empty");
        }
        if self.public_host.len() > 253
            || self
                .public_host
                .chars()
                .any(|character| character.is_control() || character.is_whitespace())
        {
            bail!("public_host contains invalid characters or is too long");
        }
        if self.tcp_port_start == 0 || self.tcp_port_start > self.tcp_port_end {
            bail!("invalid TCP port range");
        }
        if self.access_token_hashes.is_empty() {
            bail!("at least one access_token_hashes entry is required");
        }
        if self.max_sessions_per_token == 0
            || self.max_sessions_per_ip == 0
            || self.max_connections_per_session == 0
            || self.max_accepts_per_minute == 0
        {
            bail!("session and connection limits must be non-zero");
        }
        let minimum_lease = self
            .heartbeat_seconds
            .checked_mul(2)
            .context("heartbeat interval is too large")?;
        if self.heartbeat_seconds == 0
            || self.lease_seconds < minimum_lease
            || self.resume_grace_seconds == 0
        {
            bail!("lease must cover at least two heartbeats and grace must be non-zero");
        }
        for hash in &self.access_token_hashes {
            if hex::decode(hash)
                .context("access token hash is not hexadecimal")?
                .len()
                != 32
            {
                bail!("access token hashes must be SHA-256 values");
            }
        }
        Ok(())
    }
}

fn resolve(base: &Path, path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_owned()
    } else {
        base.join(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_missing_token_hash() {
        assert!(RelayConfig::default().validate().is_err());
    }

    #[test]
    fn accepts_valid_defaults_with_token() {
        let mut config = RelayConfig::default();
        config.access_token_hashes.push("00".repeat(32));
        assert!(config.validate().is_ok());
    }
}
