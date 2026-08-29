use std::sync::Arc;

use prometheus_client::{
    encoding::text::encode,
    metrics::{counter::Counter, gauge::Gauge},
    registry::Registry,
};

#[derive(Clone)]
pub struct RelayMetrics {
    registry: Arc<Registry>,
    pub active_sessions: Gauge,
    pub active_connections: Gauge,
    pub sessions_total: Counter,
    pub connections_total: Counter,
    pub rejected_total: Counter,
    pub bytes_guest_to_host: Counter,
    pub bytes_host_to_guest: Counter,
}

impl RelayMetrics {
    pub fn new() -> Self {
        let mut registry = Registry::default();
        let active_sessions = Gauge::default();
        let active_connections = Gauge::default();
        let sessions_total = Counter::default();
        let connections_total = Counter::default();
        let rejected_total = Counter::default();
        let bytes_guest_to_host = Counter::default();
        let bytes_host_to_guest = Counter::default();

        registry.register(
            "bta_anywhere_active_sessions",
            "Currently registered relay sessions.",
            active_sessions.clone(),
        );
        registry.register(
            "bta_anywhere_active_connections",
            "Currently active guest tunnel streams.",
            active_connections.clone(),
        );
        registry.register(
            "bta_anywhere_sessions",
            "Relay sessions created.",
            sessions_total.clone(),
        );
        registry.register(
            "bta_anywhere_connections",
            "Guest TCP connections accepted.",
            connections_total.clone(),
        );
        registry.register(
            "bta_anywhere_rejected",
            "Registration or connection attempts rejected.",
            rejected_total.clone(),
        );
        registry.register(
            "bta_anywhere_bytes_guest_to_host",
            "Bytes forwarded from guests to hosts.",
            bytes_guest_to_host.clone(),
        );
        registry.register(
            "bta_anywhere_bytes_host_to_guest",
            "Bytes forwarded from hosts to guests.",
            bytes_host_to_guest.clone(),
        );

        Self {
            registry: Arc::new(registry),
            active_sessions,
            active_connections,
            sessions_total,
            connections_total,
            rejected_total,
            bytes_guest_to_host,
            bytes_host_to_guest,
        }
    }

    pub fn encode(&self) -> Result<String, std::fmt::Error> {
        let mut output = String::new();
        encode(&mut output, &self.registry)?;
        Ok(output)
    }
}
