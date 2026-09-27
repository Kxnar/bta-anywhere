use std::{collections::HashSet, fs, process::Command, sync::Arc, thread};

use bta_anywhere_coordinator::{
    AllocateRequest, BoundPort, Config, Coordinator, Error, HeartbeatRequest, Probe, RedeemRequest,
    RelayConfig, ReleaseRequest,
};
use bta_anywhere_coordinator_protocol::{Mode, inspect_unverified};
use ring::{rand::SystemRandom, signature::Ed25519KeyPair};
use tempfile::TempDir;

fn fixture(relays: usize, ports: u16) -> (TempDir, Config) {
    let tmp = TempDir::new().unwrap();
    let path = |name: &str| tmp.path().join(name).display().to_string();
    fs::write(
        path("host.token"),
        b"host-host-host-host-host-host-host-host",
    )
    .unwrap();
    let key = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new()).unwrap();
    fs::write(path("signing.pk8"), key.as_ref()).unwrap();
    let relays = (0..relays)
        .map(|i| {
            fs::write(
                path(&format!("relay-{i}.token")),
                format!("relay-{i}-credential-at-least-32-bytes-secret"),
            )
            .unwrap();
            RelayConfig {
                id: format!("relay-{i}"),
                credential_file: path(&format!("relay-{i}.token")),
                first_managed_port: 30500,
                last_managed_port: 30500 + ports - 1,
                max_capacity: ports as u32,
                allow_legacy: true,
                allow_encrypted: true,
            }
        })
        .collect();
    let config = Config {
        bind: "127.0.0.1:0".into(),
        database: path("coordinator.sqlite"),
        tls_cert_file: "unused".into(),
        tls_key_file: "unused".into(),
        host_credential_file: path("host.token"),
        signing_key_file: path("signing.pk8"),
        key_id: "test-key".into(),
        heartbeat_interval_millis: 5000,
        max_probe_age_millis: 10000,
        relays,
    };
    Coordinator::initialize(config.clone()).unwrap();
    (tmp, config)
}
fn heartbeat(
    coordinator: &Coordinator,
    id: &str,
    bound_ports: Vec<BoundPort>,
    capacity: u32,
    now: i64,
) {
    coordinator
        .heartbeat(
            id,
            HeartbeatRequest {
                bound_ports,
                capacity,
            },
            now,
        )
        .unwrap();
}
fn allocate(
    coordinator: &Coordinator,
    now: i64,
    samples: &[(usize, u32)],
) -> bta_anywhere_coordinator::AllocateResponse {
    coordinator
        .allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: samples
                    .iter()
                    .map(|(id, rtt)| Probe {
                        relay_id: format!("relay-{id}"),
                        rtt_millis: *rtt,
                        measured_at_epoch_millis: now,
                    })
                    .collect(),
            },
            now,
        )
        .unwrap()
}
fn redeem(
    coordinator: &Coordinator,
    response: &bta_anywhere_coordinator::AllocateResponse,
    now: i64,
    generation: u64,
) -> String {
    coordinator
        .redeem(
            RedeemRequest {
                ticket: response.ticket.clone(),
                relay_id: response.relay_id.clone(),
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                public_port: response.public_port,
                generation,
            },
            now,
        )
        .unwrap()
        .lease_id
}

#[test]
fn three_relay_selection_capacity_failure_restart_and_reconciliation() {
    let (_tmp, config) = fixture(3, 1);
    let c = Coordinator::open(config.clone()).unwrap();
    let now = 1_000_000;
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: vec![Probe {
                    relay_id: "relay-0".into(),
                    rtt_millis: 10,
                    measured_at_epoch_millis: now
                }]
            },
            now
        )
        .unwrap_err(),
        Error::Unavailable
    );
    for i in 0..3 {
        heartbeat(&c, &format!("relay-{i}"), vec![], 1, now);
    }
    let samples = &[(0, 40), (1, 10), (2, 20)];
    let first = allocate(&c, now + 1, samples);
    assert_eq!(first.relay_id, "relay-1");
    let lease = redeem(&c, &first, now + 2, 1);
    let replay = c.redeem(
        RedeemRequest {
            ticket: first.ticket.clone(),
            relay_id: first.relay_id.clone(),
            client_instance_id: "host-1".into(),
            mode: Mode::Encrypted,
            public_port: first.public_port,
            generation: 2,
        },
        now + 3,
    );
    assert_eq!(replay.unwrap_err(), Error::Conflict);
    let second = allocate(&c, now + 4, samples);
    assert_eq!(second.relay_id, "relay-2");
    // Relay 0 becomes stale; relay 1 remains occupied; relay 2 reports no capacity.
    heartbeat(
        &c,
        "relay-1",
        vec![BoundPort {
            lease_id: lease.clone(),
            port: first.public_port,
            generation: 2,
        }],
        1,
        now + 11_000,
    );
    heartbeat(&c, "relay-2", vec![], 0, now + 11_000);
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: samples
                    .iter()
                    .map(|(i, r)| Probe {
                        relay_id: format!("relay-{i}"),
                        rtt_millis: *r,
                        measured_at_epoch_millis: now + 11_001
                    })
                    .collect()
            },
            now + 11_001
        )
        .unwrap_err(),
        Error::Unavailable
    );
    drop(c);
    let c = Coordinator::open(config.clone()).unwrap();
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: vec![Probe {
                    relay_id: "relay-1".into(),
                    rtt_millis: 1,
                    measured_at_epoch_millis: now + 11_002
                }]
            },
            now + 11_002
        )
        .unwrap_err(),
        Error::Unavailable
    );
    heartbeat(
        &c,
        "relay-1",
        vec![BoundPort {
            lease_id: lease.clone(),
            port: first.public_port,
            generation: 3,
        }],
        1,
        now + 11_003,
    );
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: vec![Probe {
                    relay_id: "relay-1".into(),
                    rtt_millis: 1,
                    measured_at_epoch_millis: now + 11_004
                }]
            },
            now + 11_004
        )
        .unwrap_err(),
        Error::Unavailable
    );
    c.release(
        ReleaseRequest {
            lease_id: lease,
            relay_id: "relay-1".into(),
            public_port: first.public_port,
            generation: 3,
        },
        now + 11_005,
    )
    .unwrap();
    let replacement = allocate(&c, now + 11_006, &[(1, 1)]);
    assert_eq!(replacement.relay_id, "relay-1");
    let _replacement_id = redeem(&c, &replacement, now + 11_007, 1);
    drop(c);
    let c = Coordinator::open(config).unwrap();
    // Startup quarantines the prior listener. An authenticated empty inventory
    // from the stopped/restarted relay proves the port can be reused.
    heartbeat(&c, "relay-1", vec![], 1, now + 11_008);
    assert_eq!(
        allocate(&c, now + 11_009, &[(1, 1)]).public_port,
        replacement.public_port
    );
}

#[test]
fn credentials_clock_rollback_bindings_and_owner_lock() {
    let (_tmp, config) = fixture(1, 2);
    let c = Coordinator::open(config.clone()).unwrap();
    assert!(!c.authenticate_host("wrong"));
    assert!(c.authenticate_host("host-host-host-host-host-host-host-host"));
    assert!(!c.authenticate_relay("relay-0", "wrong"));
    assert!(c.authenticate_relay("relay-0", "relay-0-credential-at-least-32-bytes-secret"));
    assert_eq!(
        Coordinator::open(config.clone()).err(),
        Some(Error::Conflict)
    );
    heartbeat(&c, "relay-0", vec![], 2, 1_000_000);
    let a = allocate(&c, 1_000_001, &[(0, 10)]);
    let claims = inspect_unverified(&a.ticket).unwrap();
    assert_eq!(claims.public_port, a.public_port);
    assert_eq!(
        c.redeem(
            RedeemRequest {
                ticket: a.ticket.clone(),
                relay_id: "relay-0".into(),
                client_instance_id: "wrong-host".into(),
                mode: Mode::Encrypted,
                public_port: a.public_port,
                generation: 1
            },
            1_000_002
        )
        .unwrap_err(),
        Error::Invalid
    );
    assert_eq!(
        c.redeem(
            RedeemRequest {
                ticket: a.ticket.clone(),
                relay_id: "relay-0".into(),
                client_instance_id: "host-1".into(),
                mode: Mode::Legacy,
                public_port: a.public_port,
                generation: 1
            },
            1_000_002
        )
        .unwrap_err(),
        Error::Invalid
    );
    assert_eq!(
        c.redeem(
            RedeemRequest {
                ticket: a.ticket.clone(),
                relay_id: "relay-0".into(),
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                public_port: a.public_port + 1,
                generation: 1
            },
            1_000_002
        )
        .unwrap_err(),
        Error::Invalid
    );
    let id = redeem(&c, &a, 1_000_003, 1);
    assert_eq!(
        c.heartbeat(
            "relay-0",
            HeartbeatRequest {
                bound_ports: vec![BoundPort {
                    lease_id: id.clone(),
                    port: a.public_port,
                    generation: 0
                }],
                capacity: 2
            },
            1_000_004
        ),
        Err(Error::Invalid)
    );
    heartbeat(
        &c,
        "relay-0",
        vec![BoundPort {
            lease_id: id.clone(),
            port: a.public_port,
            generation: 2,
        }],
        2,
        1_000_005,
    );
    assert_eq!(
        c.release(
            ReleaseRequest {
                lease_id: id.clone(),
                relay_id: "relay-0".into(),
                public_port: a.public_port,
                generation: 1
            },
            1_000_006
        ),
        Err(Error::Conflict)
    );
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: vec![Probe {
                    relay_id: "relay-0".into(),
                    rtt_millis: 1,
                    measured_at_epoch_millis: 1_000_004
                }]
            },
            1_000_004
        )
        .unwrap_err(),
        Error::ClockRollback
    );
    assert_eq!(
        c.allocate(
            AllocateRequest {
                client_instance_id: "host-1".into(),
                mode: Mode::Encrypted,
                probes: vec![Probe {
                    relay_id: "relay-0".into(),
                    rtt_millis: 1,
                    measured_at_epoch_millis: i64::MIN
                }]
            },
            1_000_007
        )
        .unwrap_err(),
        Error::Unavailable
    );
    c.release(
        ReleaseRequest {
            lease_id: id.clone(),
            relay_id: "relay-0".into(),
            public_port: a.public_port,
            generation: 2,
        },
        1_000_007,
    )
    .unwrap();
    c.release(
        ReleaseRequest {
            lease_id: id,
            relay_id: "relay-0".into(),
            public_port: a.public_port,
            generation: 2,
        },
        1_000_008,
    )
    .unwrap();
}

#[test]
fn ten_thousand_contended_attempts_hundred_workers_never_duplicate_active_port() {
    let (_tmp, config) = fixture(1, 100);
    let c = Arc::new(Coordinator::open(config).unwrap());
    heartbeat(&c, "relay-0", vec![], 100, 1_000_000);
    let handles: Vec<_> = (0..100)
        .map(|_| {
            let c = c.clone();
            thread::spawn(move || {
                let mut successes = Vec::new();
                for _ in 0..100 {
                    if let Ok(response) = c.allocate(
                        AllocateRequest {
                            client_instance_id: "host-1".into(),
                            mode: Mode::Encrypted,
                            probes: vec![Probe {
                                relay_id: "relay-0".into(),
                                rtt_millis: 10,
                                measured_at_epoch_millis: 1_000_001,
                            }],
                        },
                        1_000_001,
                    ) {
                        successes.push((response.relay_id, response.public_port));
                    }
                }
                successes
            })
        })
        .collect();
    let all: Vec<_> = handles
        .into_iter()
        .flat_map(|h| h.join().unwrap())
        .collect();
    assert_eq!(all.len(), 100);
    assert_eq!(all.iter().collect::<HashSet<_>>().len(), 100);
}

#[test]
fn sqlite_backup_is_consistent_and_refuses_overwrite() {
    let (tmp, config) = fixture(1, 2);
    let c = Coordinator::open(config.clone()).unwrap();
    heartbeat(&c, "relay-0", vec![], 2, 1_000_000);
    let allocated = allocate(&c, 1_000_001, &[(0, 10)]);
    let expected = inspect_unverified(&allocated.ticket).unwrap().lease_id;
    let config_path = tmp.path().join("coordinator.toml");
    fs::write(&config_path, toml::to_string(&config).unwrap()).unwrap();
    let output = tmp.path().join("snapshot.sqlite");
    let run = || {
        Command::new(env!("CARGO_BIN_EXE_bta-anywhere-coordinator"))
            .arg("backup")
            .arg("--config")
            .arg(&config_path)
            .arg("--output")
            .arg(&output)
            .output()
            .unwrap()
    };
    assert!(run().status.success());
    let backup = rusqlite::Connection::open(&output).unwrap();
    let found: String = backup
        .query_row("SELECT id FROM leases WHERE state='reserved'", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(found, expected);
    assert!(!run().status.success());
}

#[test]
fn database_loss_fails_closed_and_explicit_init_refuses_overwrite() {
    let (tmp, config) = fixture(1, 2);
    let config_path = tmp.path().join("coordinator.toml");
    fs::write(&config_path, toml::to_string(&config).unwrap()).unwrap();
    let init = || {
        Command::new(env!("CARGO_BIN_EXE_bta-anywhere-coordinator"))
            .arg("init-db")
            .arg("--config")
            .arg(&config_path)
            .output()
            .unwrap()
    };
    assert!(!init().status.success());
    fs::remove_file(&config.database).unwrap();
    assert_eq!(
        Coordinator::open(config.clone()).err(),
        Some(Error::Storage)
    );
    assert!(init().status.success());
    assert!(Coordinator::open(config).is_ok());
}

#[test]
fn missing_uniqueness_index_fails_closed() {
    let (_tmp, config) = fixture(1, 2);
    let db = rusqlite::Connection::open(&config.database).unwrap();
    db.execute_batch("DROP INDEX one_active_port").unwrap();
    drop(db);
    assert_eq!(Coordinator::open(config).err(), Some(Error::Storage));
}
