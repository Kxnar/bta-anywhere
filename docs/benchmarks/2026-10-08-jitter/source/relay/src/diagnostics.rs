//! Opt-in, payload-free connection sampling. The guard bounds task lifetime.
use std::time::Duration;

pub struct Sampler(Option<tokio::task::JoinHandle<()>>);

impl Sampler {
    pub fn start(connection: &quinn::Connection) -> Self {
        if std::env::var_os("BTA_TRANSPORT_PROFILE").is_none() {
            return Self(None);
        }
        let connection = connection.clone();
        Self(Some(tokio::spawn(async move {
            let period = Duration::from_millis(500);
            loop {
                let due = tokio::time::Instant::now() + period;
                tokio::time::sleep_until(due).await;
                let delay = due.elapsed().as_micros();
                let s = connection.stats();
                eprintln!(
                    "BTA_DIAGNOSTIC {}",
                    serde_json::json!({
                        "kind": "relay", "loop_delay_us": delay,
                        "rtt_us": s.path.rtt.as_micros(), "cwnd": s.path.cwnd,
                        "sent": s.path.sent_packets, "lost": s.path.lost_packets,
                        "lost_bytes": s.path.lost_bytes, "congestion": s.path.congestion_events,
                        "udp_tx": s.udp_tx.bytes, "udp_rx": s.udp_rx.bytes,
                        "tx_blocked": s.frame_tx.data_blocked, "rx_blocked": s.frame_rx.data_blocked,
                        "tx_stream_blocked": s.frame_tx.stream_data_blocked,
                        "rx_stream_blocked": s.frame_rx.stream_data_blocked,
                        "tx_max_data": s.frame_tx.max_data, "rx_max_data": s.frame_rx.max_data,
                        "tx_max_stream_data": s.frame_tx.max_stream_data,
                        "rx_max_stream_data": s.frame_rx.max_stream_data
                    })
                );
                if connection.close_reason().is_some() {
                    break;
                }
            }
        })))
    }
}

impl Drop for Sampler {
    fn drop(&mut self) {
        if let Some(task) = self.0.take() {
            task.abort();
        }
    }
}
