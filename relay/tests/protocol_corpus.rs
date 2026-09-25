#[path = "../src/protocol.rs"]
#[allow(dead_code)]
mod protocol;

use std::{
    env,
    fs::File,
    io::{BufRead, BufReader, BufWriter, Write},
};

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Case {
    id: usize,
    frame_hex: String,
    target: String,
    phase: String,
    expected_session: String,
    #[serde(default)]
    feature_negotiated: bool,
    #[serde(default)]
    active_connection_id: Option<String>,
    #[serde(default)]
    notice_already_sent: bool,
    #[serde(default)]
    copied_bytes: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Outcome {
    id: usize,
    accepted: bool,
    semantic: Option<Value>,
    typed_accepted: bool,
    typed_semantic: Option<Value>,
    state_outcome: String,
}

fn valid_remote_address(value: &str) -> bool {
    if value.trim().is_empty() || value.len() > 128 {
        return false;
    }
    let Some((host, port)) = value.rsplit_once(':') else {
        return false;
    };
    let host = if host.starts_with('[') && host.ends_with(']') {
        &host[1..host.len() - 1]
    } else if !host.contains(':') {
        host
    } else {
        return false;
    };
    !host.trim().is_empty() && port.parse::<u16>().is_ok_and(|port| port > 0)
}

fn control_semantic(message: protocol::ClientControl, case: &Case) -> (Value, String) {
    let phase = case.phase.as_str();
    match message {
        protocol::ClientControl::Register {
            version,
            features,
            access_token,
            client_instance_id,
            resume_token,
        } => {
            let outcome = if phase != "pre" {
                "already_registered"
            } else if version != protocol::PROTOCOL_VERSION {
                "unsupported_version"
            } else if access_token != "synthetic-test-token" {
                "authentication_failed"
            } else if client_instance_id.is_empty() || client_instance_id.len() > 128 {
                "registration_rejected"
            } else {
                "register"
            };
            (
                serde_json::json!({"type":"register","version":version,"accessToken":access_token,
                "clientInstanceId":client_instance_id,"resumeToken":resume_token,
                "features":features}),
                outcome.into(),
            )
        }
        protocol::ClientControl::Ping { sequence } => (
            serde_json::json!({"type":"ping","sequence":sequence}),
            if phase == "active" {
                "pong"
            } else {
                "registration_required"
            }
            .into(),
        ),
        protocol::ClientControl::Close { reason } => (
            serde_json::json!({"type":"close","reason":reason}),
            if phase == "active" {
                "close"
            } else {
                "registration_required"
            }
            .into(),
        ),
        protocol::ClientControl::StreamEof {
            connection_id,
            bytes,
        } => {
            let state = if phase != "active" {
                "registration_required"
            } else if !case.feature_negotiated {
                "feature_not_negotiated"
            } else if case.active_connection_id.as_deref() != Some(connection_id.as_str()) {
                "late_notice"
            } else if case.notice_already_sent || bytes < case.copied_bytes {
                "notice_rejected"
            } else {
                "notice_accepted"
            };
            (serde_json::json!({"type":"streamEof","connectionId":connection_id,
                "bytes":bytes}), state.into())
        }
    }
}

async fn typed_outcome(frame: &[u8], case: &Case) -> (Option<Value>, String) {
    let mut reader = frame;
    if case.target == "control" {
        match protocol::read_json::<_, protocol::ClientControl>(&mut reader).await {
            Ok(message) if reader.is_empty() => {
                let (semantic, state) = control_semantic(message, case);
                (Some(semantic), state)
            }
            _ => (None, "syntax_rejected".into()),
        }
    } else if case.target == "connection" {
        match protocol::read_json::<_, protocol::ConnectionOpen>(&mut reader).await {
            Ok(header) if reader.is_empty() => {
                let valid = header.version == protocol::PROTOCOL_VERSION
                    && header.session_id == case.expected_session
                    && !header.session_id.trim().is_empty()
                    && header.session_id.len() <= 128
                    && !header.connection_id.trim().is_empty()
                    && header.connection_id.len() <= 128
                    && valid_remote_address(&header.remote_address);
                let semantic = serde_json::to_value(header).expect("serialize connection header");
                (
                    Some(semantic),
                    if valid { "open" } else { "header_rejected" }.into(),
                )
            }
            _ => (None, "syntax_rejected".into()),
        }
    } else {
        panic!("unknown target: {}", case.target);
    }
}

#[tokio::test]
async fn shared_corpus() {
    let Ok(corpus_path) = env::var("BTA_PROTOCOL_CORPUS") else {
        return;
    };
    let output_path =
        env::var("BTA_PROTOCOL_RUST_OUTPUT").expect("output path required with corpus");
    let input = BufReader::new(File::open(corpus_path).expect("open corpus"));
    let mut output = BufWriter::new(File::create(output_path).expect("create Rust output"));
    for line in input.lines() {
        let case: Case =
            serde_json::from_str(&line.expect("read corpus line")).expect("valid case");
        let frame = hex::decode(&case.frame_hex).expect("valid hex case");
        let mut reader = frame.as_slice();
        let semantic = match protocol::read_json::<_, Value>(&mut reader).await {
            Ok(value) if value.is_object() && reader.is_empty() => Some(value),
            _ => None,
        };
        let (typed_semantic, state_outcome) = typed_outcome(&frame, &case).await;
        let outcome = Outcome {
            id: case.id,
            accepted: semantic.is_some(),
            semantic,
            typed_accepted: typed_semantic.is_some(),
            typed_semantic,
            state_outcome,
        };
        serde_json::to_writer(&mut output, &outcome).expect("write Rust outcome");
        writeln!(output).expect("write line ending");
    }
    output.flush().expect("flush Rust output");
}
