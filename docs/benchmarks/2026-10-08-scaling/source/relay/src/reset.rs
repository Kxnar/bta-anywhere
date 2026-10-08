//! Restart-stable QUIC reset authentication, tied to the existing TLS identity.
use std::sync::Arc;

use anyhow::{Result, anyhow};
use quinn::{ConnectionId, ConnectionIdGenerator, EndpointConfig};
use rand::RngCore;
use ring::{hkdf, hmac};

struct RandomCid;

impl ConnectionIdGenerator for RandomCid {
    fn generate_cid(&mut self) -> ConnectionId {
        let mut bytes = [0_u8; 16];
        rand::rng().fill_bytes(&mut bytes);
        ConnectionId::new(&bytes)
    }

    fn cid_len(&self) -> usize {
        16
    }

    fn cid_lifetime(&self) -> Option<std::time::Duration> {
        None
    }
}

fn reset_key(private_key_der: &[u8]) -> Result<hmac::Key> {
    let salt = hkdf::Salt::new(hkdf::HKDF_SHA256, &[]);
    let prk = salt.extract(private_key_der);
    let info = [b"bta-anywhere/quic-stateless-reset/v1".as_slice()];
    let material = prk
        .expand(&info, hkdf::HKDF_SHA256)
        .map_err(|_| anyhow!("could not derive QUIC reset key"))?;
    let mut bytes = [0_u8; 32];
    material
        .fill(&mut bytes)
        .map_err(|_| anyhow!("could not fill QUIC reset key"))?;
    Ok(hmac::Key::new(hmac::HMAC_SHA256, &bytes))
}

pub fn endpoint_config(private_key_der: &[u8]) -> Result<EndpointConfig> {
    let mut config = EndpointConfig::new(Arc::new(reset_key(private_key_der)?));
    // The default hashed-CID validator has a fresh key after each restart and
    // would discard old CIDs before reaching stateless reset. Random 128-bit
    // CIDs stay recognizable without reusing a small, keyed nonce space. Quinn
    // retains reset rate limiting and its smaller-than-request response bound.
    config.cid_generator(|| Box::new(RandomCid));
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_generator_accepts_a_previous_instances_full_entropy_cid() {
        let old = RandomCid.generate_cid();
        assert_eq!(old.len(), 16);
        assert!(RandomCid.validate(&old).is_ok());
    }

    #[test]
    fn restarted_identity_authenticates_the_same_reset_token() {
        let first = reset_key(b"test private identity one").unwrap();
        let restarted = reset_key(b"test private identity one").unwrap();
        let cid = b"test connection id";
        let token = hmac::sign(&first, cid);
        assert!(hmac::verify(&restarted, cid, token.as_ref()).is_ok());
        assert!(hmac::verify(&restarted, b"different connection", token.as_ref()).is_err());
    }

    #[test]
    fn identity_rotation_does_not_authenticate_an_old_reset() {
        let first = reset_key(b"test private identity one").unwrap();
        let rotated = reset_key(b"test private identity two").unwrap();
        let cid = b"test connection id";
        let token = hmac::sign(&first, cid);
        assert!(hmac::verify(&rotated, cid, token.as_ref()).is_err());
    }
}
