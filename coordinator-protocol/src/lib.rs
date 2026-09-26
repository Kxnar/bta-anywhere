//! Signed, canonical allocation tickets. Durable lease redemption is a separate coordinator step.

use std::collections::HashMap;

use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ring::signature::{ED25519, Ed25519KeyPair, KeyPair, UnparsedPublicKey};
use serde::{Deserialize, Serialize};

pub const PREFIX: &str = "BTACT1:";
pub const MAX_TICKET_LENGTH: usize = 2048;
pub const MAX_LIFETIME_MILLIS: i64 = 30_000;

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Legacy,
    Encrypted,
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TicketClaims {
    pub version: u8,
    pub lease_id: String,
    pub key_id: String,
    pub relay_id: String,
    pub public_port: u16,
    pub client_instance_id: String,
    pub mode: Mode,
    pub issued_at_epoch_millis: i64,
    pub expires_at_epoch_millis: i64,
}

#[derive(Clone, Copy, Debug)]
pub struct Expected<'a> {
    pub relay_id: &'a str,
    pub public_port: u16,
    pub client_instance_id: &'a str,
    pub mode: Mode,
}

#[derive(Debug, PartialEq, Eq)]
pub struct TicketError;

impl std::fmt::Display for TicketError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("allocation ticket rejected")
    }
}

impl std::error::Error for TicketError {}

impl TicketClaims {
    fn validate(&self) -> Result<(), TicketError> {
        let id = URL_SAFE_NO_PAD
            .decode(&self.lease_id)
            .map_err(|_| TicketError)?;
        if self.version != 1
            || id.len() != 16
            || URL_SAFE_NO_PAD.encode(id) != self.lease_id
            || !valid_ascii_id(&self.key_id, 32)
            || !valid_ascii_id(&self.relay_id, 64)
            || !valid_ascii_id(&self.client_instance_id, 128)
            || self.public_port == 0
            || self.issued_at_epoch_millis <= 0
            || self.expires_at_epoch_millis <= self.issued_at_epoch_millis
            || self.expires_at_epoch_millis - self.issued_at_epoch_millis > MAX_LIFETIME_MILLIS
        {
            return Err(TicketError);
        }
        Ok(())
    }
}

fn valid_ascii_id(value: &str, max: usize) -> bool {
    !value.is_empty()
        && value.len() <= max
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

/// Sign only after a durable reservation has committed. This function has no lease side effects.
pub fn sign(claims: &TicketClaims, key: &Ed25519KeyPair) -> Result<String, TicketError> {
    claims.validate()?;
    let payload = serde_json::to_vec(claims).map_err(|_| TicketError)?;
    let signature = key.sign(&payload);
    let ticket = format!(
        "{PREFIX}{}.{}",
        URL_SAFE_NO_PAD.encode(payload),
        URL_SAFE_NO_PAD.encode(signature.as_ref())
    );
    if ticket.len() > MAX_TICKET_LENGTH {
        return Err(TicketError);
    }
    Ok(ticket)
}

/// Verify signature and exact bindings before contacting the coordinator to redeem a lease.
pub fn verify(
    ticket: &str,
    keys: &HashMap<String, Vec<u8>>,
    expected: Expected<'_>,
    now_epoch_millis: i64,
) -> Result<TicketClaims, TicketError> {
    if ticket.len() > MAX_TICKET_LENGTH {
        return Err(TicketError);
    }
    let encoded = ticket.strip_prefix(PREFIX).ok_or(TicketError)?;
    let (payload_text, signature_text) = encoded.split_once('.').ok_or(TicketError)?;
    let payload = URL_SAFE_NO_PAD
        .decode(payload_text)
        .map_err(|_| TicketError)?;
    let signature = URL_SAFE_NO_PAD
        .decode(signature_text)
        .map_err(|_| TicketError)?;
    if payload.len() > 1024
        || signature.len() != 64
        || URL_SAFE_NO_PAD.encode(&payload) != payload_text
        || URL_SAFE_NO_PAD.encode(&signature) != signature_text
    {
        return Err(TicketError);
    }
    let claims: TicketClaims = serde_json::from_slice(&payload).map_err(|_| TicketError)?;
    claims.validate()?;
    if serde_json::to_vec(&claims).map_err(|_| TicketError)? != payload {
        return Err(TicketError);
    }
    let key = keys.get(&claims.key_id).ok_or(TicketError)?;
    UnparsedPublicKey::new(&ED25519, key)
        .verify(&payload, &signature)
        .map_err(|_| TicketError)?;
    if claims.relay_id != expected.relay_id
        || claims.public_port != expected.public_port
        || claims.client_instance_id != expected.client_instance_id
        || claims.mode != expected.mode
        || now_epoch_millis < claims.issued_at_epoch_millis
        || now_epoch_millis >= claims.expires_at_epoch_millis
    {
        return Err(TicketError);
    }
    Ok(claims)
}

pub fn public_key_bytes(key: &Ed25519KeyPair) -> Vec<u8> {
    key.public_key().as_ref().to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::rand::SystemRandom;

    fn new_key() -> Ed25519KeyPair {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&SystemRandom::new()).unwrap();
        Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap()
    }

    fn claims() -> TicketClaims {
        TicketClaims {
            version: 1,
            lease_id: URL_SAFE_NO_PAD.encode([7_u8; 16]),
            key_id: "operator-key-1".into(),
            relay_id: "eu-west-1".into(),
            public_port: 30_500,
            client_instance_id: "host-1".into(),
            mode: Mode::Encrypted,
            issued_at_epoch_millis: 1_000_000,
            expires_at_epoch_millis: 1_030_000,
        }
    }

    fn expected<'a>() -> Expected<'a> {
        Expected {
            relay_id: "eu-west-1",
            public_port: 30_500,
            client_instance_id: "host-1",
            mode: Mode::Encrypted,
        }
    }

    #[test]
    fn signed_ticket_requires_exact_key_and_bindings() {
        let key = new_key();
        let ticket = sign(&claims(), &key).unwrap();
        let mut keys = HashMap::from([("operator-key-1".into(), public_key_bytes(&key))]);
        assert_eq!(
            verify(&ticket, &keys, expected(), 1_000_001).unwrap(),
            claims()
        );
        assert!(verify(&ticket, &keys, expected(), 1_030_000).is_err());
        assert!(verify(&ticket, &keys, expected(), 999_999).is_err());
        assert!(
            verify(
                &ticket,
                &keys,
                Expected {
                    relay_id: "wrong",
                    ..expected()
                },
                1_000_001
            )
            .is_err()
        );
        assert!(
            verify(
                &ticket,
                &keys,
                Expected {
                    mode: Mode::Legacy,
                    ..expected()
                },
                1_000_001
            )
            .is_err()
        );
        assert!(
            verify(
                &ticket,
                &keys,
                Expected {
                    public_port: 30_501,
                    ..expected()
                },
                1_000_001
            )
            .is_err()
        );
        assert!(
            verify(
                &ticket,
                &keys,
                Expected {
                    client_instance_id: "other",
                    ..expected()
                },
                1_000_001
            )
            .is_err()
        );
        keys.insert("operator-key-1".into(), public_key_bytes(&new_key()));
        assert!(verify(&ticket, &keys, expected(), 1_000_001).is_err());
    }

    #[test]
    fn malformed_tampered_and_noncanonical_tickets_fail_closed() {
        let key = new_key();
        let ticket = sign(&claims(), &key).unwrap();
        let keys = HashMap::from([("operator-key-1".into(), public_key_bytes(&key))]);
        assert!(verify("BTACT1:bad", &keys, expected(), 1_000_001).is_err());
        assert!(
            verify(
                &"x".repeat(MAX_TICKET_LENGTH + 1),
                &keys,
                expected(),
                1_000_001
            )
            .is_err()
        );
        let mut altered = ticket.clone().into_bytes();
        let index = altered.len() - 1;
        altered[index] = if altered[index] == b'A' { b'B' } else { b'A' };
        assert!(
            verify(
                &String::from_utf8(altered).unwrap(),
                &keys,
                expected(),
                1_000_001
            )
            .is_err()
        );
        let (payload, sig) = ticket
            .strip_prefix(PREFIX)
            .unwrap()
            .split_once('.')
            .unwrap();
        let raw = URL_SAFE_NO_PAD.decode(payload).unwrap();
        let mut text = String::from_utf8(raw).unwrap();
        text = text.replace("\"version\":1", "\"version\":1,\"version\":1");
        let duplicate = format!("{PREFIX}{}.{}", URL_SAFE_NO_PAD.encode(text), sig);
        assert!(verify(&duplicate, &keys, expected(), 1_000_001).is_err());
    }

    #[test]
    fn invalid_claims_are_not_signed() {
        let key = new_key();
        let mut value = claims();
        value.expires_at_epoch_millis += 1;
        assert!(sign(&value, &key).is_err());
        value = claims();
        value.lease_id = "not-canonical".into();
        assert!(sign(&value, &key).is_err());
        value = claims();
        value.version = 2;
        assert!(sign(&value, &key).is_err());
    }
}
