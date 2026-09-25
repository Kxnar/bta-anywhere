use std::{collections::HashSet, fmt};

use serde::{
    Deserialize, Deserializer, Serialize,
    de::{DeserializeOwned, IgnoredAny, MapAccess, Visitor},
};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const PROTOCOL_VERSION: u16 = 1;
pub const ALPN: &[u8] = b"bta-anywhere/1";
pub const MAX_FRAME_SIZE: usize = 64 * 1024;

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ClientControl {
    #[serde(rename = "register")]
    Register {
        version: u16,
        #[serde(rename = "accessToken")]
        access_token: String,
        #[serde(rename = "clientInstanceId")]
        client_instance_id: String,
        #[serde(rename = "resumeToken")]
        resume_token: Option<String>,
    },
    #[serde(rename = "ping")]
    Ping { sequence: u64 },
    #[serde(rename = "close")]
    Close { reason: String },
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ServerControl<'a> {
    #[serde(rename = "registered")]
    Registered {
        #[serde(rename = "sessionId")]
        session_id: &'a str,
        #[serde(rename = "publicHost")]
        public_host: &'a str,
        #[serde(rename = "publicPort")]
        public_port: u16,
        #[serde(rename = "resumeToken")]
        resume_token: &'a str,
        #[serde(rename = "leaseSeconds")]
        lease_seconds: u64,
    },
    #[serde(rename = "pong")]
    Pong { sequence: u64 },
    #[serde(rename = "error")]
    Error {
        code: &'a str,
        message: &'a str,
        retryable: bool,
    },
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionOpen {
    pub version: u16,
    pub session_id: String,
    pub connection_id: String,
    pub remote_address: String,
}

#[derive(Debug, Error)]
pub enum FrameError {
    #[error("I/O while reading or writing a frame: {0}")]
    Io(#[from] std::io::Error),
    #[error("frame length {0} exceeds the {MAX_FRAME_SIZE}-byte limit")]
    TooLarge(usize),
    #[error("invalid JSON frame: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid UTF-8 JSON frame")]
    InvalidUtf8(#[from] std::str::Utf8Error),
    #[error("JSON frame must contain an object")]
    NotObject,
}

struct TopLevelProtocolObject;

impl<'de> Deserialize<'de> for TopLevelProtocolObject {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct ObjectVisitor;

        impl<'de> Visitor<'de> for ObjectVisitor {
            type Value = TopLevelProtocolObject;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a protocol JSON object")
            }

            fn visit_map<M>(self, mut map: M) -> Result<Self::Value, M::Error>
            where
                M: MapAccess<'de>,
            {
                let mut seen = HashSet::new();
                while let Some(name) = map.next_key::<String>()? {
                    if is_known_top_level_property(&name) && !seen.insert(name.clone()) {
                        return Err(serde::de::Error::custom(format!(
                            "repeated protocol property: {name}"
                        )));
                    }
                    map.next_value::<IgnoredAny>()?;
                }
                Ok(TopLevelProtocolObject)
            }
        }

        deserializer.deserialize_map(ObjectVisitor)
    }
}

fn is_known_top_level_property(name: &str) -> bool {
    matches!(
        name,
        "type"
            | "version"
            | "accessToken"
            | "clientInstanceId"
            | "resumeToken"
            | "sequence"
            | "reason"
            | "sessionId"
            | "publicHost"
            | "publicPort"
            | "leaseSeconds"
            | "code"
            | "message"
            | "retryable"
            | "connectionId"
            | "remoteAddress"
    )
}

pub async fn read_json<R, T>(reader: &mut R) -> Result<T, FrameError>
where
    R: AsyncRead + Unpin,
    T: DeserializeOwned,
{
    let length = reader.read_u32().await? as usize;
    if length > MAX_FRAME_SIZE {
        return Err(FrameError::TooLarge(length));
    }
    let mut bytes = vec![0_u8; length];
    reader.read_exact(&mut bytes).await?;
    std::str::from_utf8(&bytes)?;
    if bytes
        .iter()
        .copied()
        .find(|byte| !byte.is_ascii_whitespace())
        != Some(b'{')
    {
        return Err(FrameError::NotObject);
    }
    serde_json::from_slice::<TopLevelProtocolObject>(&bytes)?;
    Ok(serde_json::from_slice(&bytes)?)
}

pub async fn write_json<W, T>(writer: &mut W, value: &T) -> Result<(), FrameError>
where
    W: AsyncWrite + Unpin,
    T: Serialize + ?Sized,
{
    let bytes = serde_json::to_vec(value)?;
    if bytes.len() > MAX_FRAME_SIZE {
        return Err(FrameError::TooLarge(bytes.len()));
    }
    writer.write_u32(bytes.len() as u32).await?;
    writer.write_all(&bytes).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct VectorDocument {
        protocol_version: u16,
        alpn: String,
        frames: Vec<FrameVector>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct FrameVector {
        name: String,
        json: String,
        payload_length: usize,
        frame_hex: String,
    }

    #[derive(Deserialize)]
    struct MalformedDocument {
        cases: Vec<MalformedVector>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct MalformedVector {
        name: String,
        frame_hex: String,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct StructuralDocument {
        schema_version: u32,
        cases: Vec<StructuralVector>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct StructuralVector {
        name: String,
        target: String,
        accepted: bool,
        frame_hex: String,
    }

    #[tokio::test]
    async fn round_trips_connection_header() {
        let expected = ConnectionOpen {
            version: PROTOCOL_VERSION,
            session_id: "session".into(),
            connection_id: "connection".into(),
            remote_address: "127.0.0.1:1234".into(),
        };
        let (mut writer, mut reader) = tokio::io::duplex(4096);
        write_json(&mut writer, &expected).await.unwrap();
        let actual: ConnectionOpen = read_json(&mut reader).await.unwrap();
        assert_eq!(actual, expected);
    }

    #[tokio::test]
    async fn rejects_oversized_frame_before_allocating_payload() {
        let (mut writer, mut reader) = tokio::io::duplex(16);
        writer.write_u32((MAX_FRAME_SIZE + 1) as u32).await.unwrap();
        assert!(matches!(
            read_json::<_, ConnectionOpen>(&mut reader).await,
            Err(FrameError::TooLarge(_))
        ));
    }

    #[tokio::test]
    async fn shared_malformed_object_vectors_are_rejected() {
        let document: MalformedDocument = serde_json::from_str(include_str!(
            "../../protocol/test-vectors/malformed-v1.json"
        ))
        .unwrap();
        for case in document.cases.iter().filter(|case| {
            case.name == "invalid-utf8-object"
                || case.name == "invalid-utf8-unknown-control"
                || case.name == "non-object-array"
                || case.name == "string-sequence"
        }) {
            let frame = hex::decode(&case.frame_hex).unwrap();
            let mut reader = frame.as_slice();
            assert!(
                read_json::<_, ClientControl>(&mut reader).await.is_err(),
                "{}",
                case.name
            );
        }
        for name in ["invalid-utf8-connection", "invalid-utf8-unknown-connection"] {
            let connection = document
                .cases
                .iter()
                .find(|case| case.name == name)
                .unwrap();
            let frame = hex::decode(&connection.frame_hex).unwrap();
            let mut reader = frame.as_slice();
            assert!(
                read_json::<_, ConnectionOpen>(&mut reader).await.is_err(),
                "{name}"
            );
        }
    }

    #[tokio::test]
    async fn shared_structural_vectors_match_v1_boundaries() {
        let document: StructuralDocument = serde_json::from_str(include_str!(
            "../../protocol/test-vectors/structural-v1.json"
        ))
        .unwrap();
        assert_eq!(document.schema_version, 1);
        for case in document.cases {
            let frame = hex::decode(&case.frame_hex).unwrap();
            let mut reader = frame.as_slice();
            let accepted = match case.target.as_str() {
                "control" => read_json::<_, ClientControl>(&mut reader).await.is_ok(),
                "connection" => read_json::<_, ConnectionOpen>(&mut reader).await.is_ok(),
                _ => panic!("unknown structural vector target"),
            };
            assert_eq!(accepted, case.accepted, "{}", case.name);
        }
    }

    #[test]
    fn shared_vectors_have_byte_exact_big_endian_framing() {
        let document: VectorDocument =
            serde_json::from_str(include_str!("../../protocol/test-vectors/framing-v1.json"))
                .unwrap();
        assert_eq!(document.protocol_version, PROTOCOL_VERSION);
        assert_eq!(document.alpn.as_bytes(), ALPN);
        for vector in document.frames {
            let frame = hex::decode(&vector.frame_hex).unwrap();
            assert!(frame.len() >= 4, "{} has no length prefix", vector.name);
            let declared = u32::from_be_bytes(frame[..4].try_into().unwrap()) as usize;
            assert_eq!(declared, vector.payload_length, "{}", vector.name);
            assert_eq!(declared, frame.len() - 4, "{}", vector.name);
            let actual: serde_json::Value = serde_json::from_slice(&frame[4..]).unwrap();
            let expected: serde_json::Value = serde_json::from_str(&vector.json).unwrap();
            assert_eq!(actual, expected, "{}", vector.name);
        }
    }

    #[tokio::test]
    async fn shared_register_vector_decodes_to_control_message() {
        let document: VectorDocument =
            serde_json::from_str(include_str!("../../protocol/test-vectors/framing-v1.json"))
                .unwrap();
        let vector = document
            .frames
            .iter()
            .find(|item| item.name == "register")
            .unwrap();
        let bytes = hex::decode(&vector.frame_hex).unwrap();
        let (mut writer, mut reader) = tokio::io::duplex(bytes.len());
        writer.write_all(&bytes).await.unwrap();
        drop(writer);
        let message: ClientControl = read_json(&mut reader).await.unwrap();
        match message {
            ClientControl::Register {
                version,
                access_token,
                client_instance_id,
                resume_token,
            } => {
                assert_eq!(version, PROTOCOL_VERSION);
                assert_eq!(access_token, "test-access-token");
                assert_eq!(client_instance_id, "11111111-2222-3333-4444-555555555555");
                assert!(resume_token.is_none());
            }
            _ => panic!("shared register vector decoded to another control message"),
        }
    }
}
