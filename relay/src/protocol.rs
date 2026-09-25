use serde::{Deserialize, Serialize, de::DeserializeOwned};
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
        #[serde(default, deserialize_with = "deserialize_registration_features")]
        features: Vec<String>,
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
    #[serde(rename = "streamEof")]
    StreamEof {
        #[serde(rename = "connectionId")]
        connection_id: String,
        #[serde(deserialize_with = "deserialize_stream_eof_bytes")]
        bytes: u64,
    },
}

fn deserialize_registration_features<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let features = Vec::<String>::deserialize(deserializer)?;
    if features.len() > 16 {
        return Err(serde::de::Error::custom(
            "registration supports at most 16 features",
        ));
    }
    if features
        .iter()
        .enumerate()
        .any(|(index, feature)| features[..index].contains(feature))
    {
        return Err(serde::de::Error::custom(
            "registration features must be unique",
        ));
    }
    Ok(features)
}

fn deserialize_stream_eof_bytes<'de, D>(deserializer: D) -> Result<u64, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let bytes = u64::deserialize(deserializer)?;
    if bytes > i64::MAX as u64 {
        return Err(serde::de::Error::custom(
            "stream completion count exceeds the Java signed long range",
        ));
    }
    Ok(bytes)
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum ServerControl<'a> {
    #[serde(rename = "registered")]
    Registered {
        #[serde(skip_serializing_if = "Option::is_none")]
        features: Option<&'a [&'a str]>,
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
                features,
                access_token,
                client_instance_id,
                resume_token,
            } => {
                assert_eq!(version, PROTOCOL_VERSION);
                assert!(features.is_empty());
                assert_eq!(access_token, "test-access-token");
                assert_eq!(client_instance_id, "11111111-2222-3333-4444-555555555555");
                assert!(resume_token.is_none());
            }
            _ => panic!("shared register vector decoded to another control message"),
        }
    }

    #[test]
    fn shared_stream_completion_vectors_have_typed_fields() {
        let document: VectorDocument =
            serde_json::from_str(include_str!("../../protocol/test-vectors/framing-v1.json"))
                .unwrap();
        let register = document
            .frames
            .iter()
            .find(|item| item.name == "register-stream-eof-bytes")
            .unwrap();
        match serde_json::from_str::<ClientControl>(&register.json).unwrap() {
            ClientControl::Register { features, .. } => {
                assert_eq!(features, ["streamEofBytes"]);
            }
            _ => panic!("feature registration decoded to another control message"),
        }

        let notice = document
            .frames
            .iter()
            .find(|item| item.name == "stream-eof")
            .unwrap();
        match serde_json::from_str::<ClientControl>(&notice.json).unwrap() {
            ClientControl::StreamEof {
                connection_id,
                bytes,
            } => {
                assert_eq!(connection_id, "connection-token");
                assert_eq!(bytes, 65_536);
            }
            _ => panic!("stream completion decoded to another control message"),
        }

        let registered = document
            .frames
            .iter()
            .find(|item| item.name == "registered-stream-eof-bytes")
            .unwrap();
        let expected: serde_json::Value = serde_json::from_str(&registered.json).unwrap();
        let actual = serde_json::to_value(ServerControl::Registered {
            features: Some(&["streamEofBytes"]),
            session_id: "session-token",
            public_host: "relay.example.test",
            public_port: 30_000,
            resume_token: "resume-token",
            lease_seconds: 90,
        })
        .unwrap();
        assert_eq!(actual, expected);

        let legacy = serde_json::to_value(ServerControl::Registered {
            features: None,
            session_id: "session-token",
            public_host: "relay.example.test",
            public_port: 30_000,
            resume_token: "resume-token",
            lease_seconds: 90,
        })
        .unwrap();
        assert!(legacy.get("features").is_none());
    }

    #[test]
    fn stream_completion_rejects_invalid_counts_and_fields() {
        for json in [
            r#"{"type":"streamEof","connectionId":"id","bytes":-1}"#,
            r#"{"type":"streamEof","connectionId":"id","bytes":1.5}"#,
            r#"{"type":"streamEof","connectionId":"id","bytes":"1"}"#,
            r#"{"type":"streamEof","connectionId":"id","bytes":9223372036854775808}"#,
            r#"{"type":"streamEof","connectionId":"id"}"#,
            r#"{"type":"streamEof","connectionId":"id","bytes":1,"bytes":2}"#,
        ] {
            assert!(
                serde_json::from_str::<ClientControl>(json).is_err(),
                "{json}"
            );
        }
        assert!(matches!(
            serde_json::from_str::<ClientControl>(
                r#"{"type":"streamEof","connectionId":"id","bytes":9223372036854775807}"#,
            )
            .unwrap(),
            ClientControl::StreamEof { bytes, .. } if bytes == i64::MAX as u64
        ));
    }

    #[test]
    fn registration_features_are_limited_to_sixteen_unique_strings() {
        let base = r#"{"type":"register","version":1,"accessToken":"token","clientInstanceId":"client","features":{}}"#;
        let sixteen = serde_json::to_string(
            &(0..16)
                .map(|index| format!("feature{index}"))
                .collect::<Vec<_>>(),
        )
        .unwrap();
        let valid = base.replace("{}", &sixteen);
        assert!(serde_json::from_str::<ClientControl>(&valid).is_ok());

        let seventeen = serde_json::to_string(
            &(0..17)
                .map(|index| format!("feature{index}"))
                .collect::<Vec<_>>(),
        )
        .unwrap();
        let excessive = base.replace("{}", &seventeen);
        assert!(serde_json::from_str::<ClientControl>(&excessive).is_err());
        let duplicate = base.replace("{}", r#"["streamEofBytes","streamEofBytes"]"#);
        assert!(serde_json::from_str::<ClientControl>(&duplicate).is_err());
        let non_string = base.replace("{}", r#"["streamEofBytes",1]"#);
        assert!(serde_json::from_str::<ClientControl>(&non_string).is_err());
    }
}
