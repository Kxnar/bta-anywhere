use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use rand::RngCore;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

pub fn generate() -> String {
    let mut bytes = [0_u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn hash(token: &str) -> [u8; 32] {
    Sha256::digest(token.as_bytes()).into()
}

pub fn hash_hex(token: &str) -> String {
    hex::encode(hash(token))
}

pub fn matches(token: &str, expected: &[u8; 32]) -> bool {
    bool::from(hash(token).ct_eq(expected))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_tokens_are_unique_and_url_safe() {
        let first = generate();
        let second = generate();
        assert_ne!(first, second);
        assert!(!first.contains('='));
        assert_eq!(first.len(), 43);
    }

    #[test]
    fn matches_only_original_token() {
        let expected = hash("correct");
        assert!(matches("correct", &expected));
        assert!(!matches("wrong", &expected));
    }
}
