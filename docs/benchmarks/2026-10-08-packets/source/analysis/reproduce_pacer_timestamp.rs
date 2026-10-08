//! Minimal timestamp feedback experiment, not a complete QUIC implementation.
//!
//! Uses the unchanged upstream `pacer.rs` under `fixtures/`, with its BSD notice
//! intact. This feeds the
//! same 31 ms ACK interval and low congestion window to two timestamp choices.
//! The control uses `now`, as the newer congestion module does. It does not
//! establish that this single line accounts for every release-level change.

#[allow(dead_code)]
#[path = "fixtures/quiche_pacer_70d6d3.rs"]
mod pacer;

use std::time::Duration;

fn run(use_current_send_time: bool) -> (f64, f64) {
    let capacity = 12_000;
    let congestion_window = 4_600.0;
    let mut srtt_seconds = 0.031;
    let mut p = pacer::Pacer::new(true, capacity, 185_483, 1_200, None);
    let epoch = p.next_time();
    let mut latest_seconds = 0.0;
    for index in 1..=12_000 {
        let now = epoch + Duration::from_millis(index * 10);
        let rate = (1.25 * congestion_window / srtt_seconds) as u64;
        p.update(capacity, rate, now);
        // Old schedule_next_packet passes zero below the initial cwnd.
        p.send(0, now);
        let recorded = if use_current_send_time { now } else { p.next_time() };
        let ack = now + Duration::from_millis(31);
        latest_seconds = ack.duration_since(recorded).as_secs_f64();
        srtt_seconds = (7.0 * srtt_seconds + latest_seconds) / 8.0;
    }
    (latest_seconds * 1_000.0, srtt_seconds * 1_000.0)
}

fn main() {
    let old = run(false);
    let control = run(true);
    assert!(old.0 > 120_000.0 && old.1 > 119_900.0);
    assert!((control.0 - 31.0).abs() < 0.001 && (control.1 - 31.0).abs() < 0.001);
    println!("{{\"ack_interval_ms\":31,\"synthetic_seconds\":120,\"old_latest_rtt_ms\":{},\"old_smoothed_rtt_ms\":{},\"current_timestamp_latest_rtt_ms\":{},\"current_timestamp_smoothed_rtt_ms\":{}}}", old.0, old.1, control.0, control.1);
}
