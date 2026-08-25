pub use crate::models::PairingPayloadV1;
use base64::engine::general_purpose::{STANDARD, URL_SAFE, URL_SAFE_NO_PAD};
use base64::Engine;
use rand::RngCore;
use std::fmt;
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

pub const MIN_TTL_SECONDS: u64 = 10;
pub const MAX_TTL_SECONDS: u64 = 600;
pub const DEFAULT_TTL_SECONDS: u64 = 120;
pub const MAX_CLOCK_SKEW_SECONDS: u64 = 30;
pub const MAX_ENCODED_URI_BYTES: usize = 4096;
pub const MAX_DECODED_JSON_BYTES: usize = 2048;
pub const MAX_NAME_LENGTH: usize = 128;
pub const CANONICAL_NONCE_BYTES: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PairingError {
    InvalidUriScheme(String),
    InvalidUriFormat(String),
    MissingDataParameter,
    EmptyData,
    PayloadTooLarge { size: usize, max: usize },
    Base64DecodeError(String),
    JsonDecodeError(String),
    UnsupportedVersion(u32),
    InvalidPayloadType(String),
    InvalidHostId(String),
    InvalidName(String),
    EmptyHost,
    InvalidHost(String),
    InvalidPort(u16),
    InvalidScheme(String),
    InvalidNonce(String),
    InvalidFingerprint(String),
    PayloadExpired { expires_at: u64, now: u64 },
    TtlExceedsMaximum { expires_at: u64, max_allowed: u64 },
    InvalidTtl { ttl: u64, min: u64, max: u64 },
}

impl fmt::Display for PairingError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PairingError::InvalidUriScheme(s) => {
                write!(f, "Invalid URI scheme '{}', expected 'hermes'", s)
            }
            PairingError::InvalidUriFormat(s) => write!(f, "Invalid pairing URI format: {}", s),
            PairingError::MissingDataParameter => {
                write!(f, "Missing 'data' query parameter in pairing URI")
            }
            PairingError::EmptyData => write!(f, "Empty 'data' query parameter in pairing URI"),
            PairingError::PayloadTooLarge { size, max } => {
                write!(
                    f,
                    "Payload size ({} bytes) exceeds maximum limit of {} bytes",
                    size, max
                )
            }
            PairingError::Base64DecodeError(e) => {
                write!(f, "Failed to decode Base64URL payload: {}", e)
            }
            PairingError::JsonDecodeError(e) => write!(f, "Failed to parse JSON payload: {}", e),
            PairingError::UnsupportedVersion(v) => {
                write!(f, "Unsupported payload version {}, expected 1", v)
            }
            PairingError::InvalidPayloadType(t) => {
                write!(f, "Invalid payload type '{}', expected 'hermes-pair'", t)
            }
            PairingError::InvalidHostId(id) => write!(f, "Invalid host UUID: '{}'", id),
            PairingError::InvalidName(msg) => write!(f, "Invalid host display name: {}", msg),
            PairingError::EmptyHost => write!(f, "Host address cannot be empty"),
            PairingError::InvalidHost(msg) => write!(f, "Invalid host address: {}", msg),
            PairingError::InvalidPort(p) => write!(f, "Invalid port number: {}", p),
            PairingError::InvalidScheme(s) => {
                write!(f, "Invalid scheme '{}', expected 'http' or 'https'", s)
            }
            PairingError::InvalidNonce(msg) => write!(f, "Invalid nonce: {}", msg),
            PairingError::InvalidFingerprint(msg) => {
                write!(f, "Invalid certificate fingerprint: {}", msg)
            }
            PairingError::PayloadExpired { expires_at, now } => {
                write!(
                    f,
                    "Pairing payload expired at timestamp {} (current time: {})",
                    expires_at, now
                )
            }
            PairingError::TtlExceedsMaximum {
                expires_at,
                max_allowed,
            } => {
                write!(
                    f,
                    "Pairing payload expiry timestamp {} exceeds maximum allowed {}",
                    expires_at, max_allowed
                )
            }
            PairingError::InvalidTtl { ttl, min, max } => {
                write!(
                    f,
                    "Invalid TTL {}s: TTL must be between {} and {} seconds",
                    ttl, min, max
                )
            }
        }
    }
}

impl std::error::Error for PairingError {}

pub fn current_unix_timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub fn generate_nonce() -> String {
    let mut bytes = [0u8; CANONICAL_NONCE_BYTES];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn validate_ttl(ttl: u64) -> Result<(), PairingError> {
    if !(MIN_TTL_SECONDS..=MAX_TTL_SECONDS).contains(&ttl) {
        return Err(PairingError::InvalidTtl {
            ttl,
            min: MIN_TTL_SECONDS,
            max: MAX_TTL_SECONDS,
        });
    }
    Ok(())
}

pub fn validate_payload(payload: &PairingPayloadV1, current_time: u64) -> Result<(), PairingError> {
    // 1. Version must be 1 or 2
    if payload.v != 1 && payload.v != 2 {
        return Err(PairingError::UnsupportedVersion(payload.v));
    }

    // 2. Payload type must be "hermes-pair"
    if payload.payload_type != "hermes-pair" {
        return Err(PairingError::InvalidPayloadType(
            payload.payload_type.clone(),
        ));
    }

    // Fingerprint validation (if present)
    if let Some(ref fp) = payload.fingerprint {
        let trimmed = fp.trim();
        if trimmed.is_empty() {
            return Err(PairingError::InvalidFingerprint(
                "Fingerprint cannot be empty".into(),
            ));
        }
        let clean = trimmed
            .trim_start_matches("SHA256:")
            .trim_start_matches("sha256:")
            .trim_start_matches("SHA-256:")
            .trim_start_matches("sha-256:")
            .replace([':', ' ', '-'], "");
        if clean.len() != 64 || !clean.chars().all(|c| c.is_ascii_hexdigit()) {
            return Err(PairingError::InvalidFingerprint(format!(
                "Invalid certificate fingerprint '{}': expected 64 hex characters (SHA-256)",
                fp
            )));
        }
    }

    // 3. Host ID must be a valid UUID (RFC 4122 standard, any version accepted)
    Uuid::parse_str(&payload.host_id).map_err(|_| {
        PairingError::InvalidHostId(format!("'{}' is not a valid UUID", payload.host_id))
    })?;

    // 4. Name: not blank, trimmed <= 128 chars, no control characters
    let trimmed_name = payload.name.trim();
    if trimmed_name.is_empty() {
        return Err(PairingError::InvalidName(
            "Host display name cannot be blank".into(),
        ));
    }
    if trimmed_name.chars().count() > MAX_NAME_LENGTH {
        return Err(PairingError::InvalidName(format!(
            "Host display name length ({}) exceeds maximum allowed {}",
            trimmed_name.chars().count(),
            MAX_NAME_LENGTH
        )));
    }
    if payload
        .name
        .chars()
        .any(|c| (c as u32) < 0x20 || (c as u32) == 0x7F)
    {
        return Err(PairingError::InvalidName(
            "Host display name contains forbidden control characters".into(),
        ));
    }

    // 5. Host: not blank, no whitespace, no forbidden chars: / \ ? # @ : control chars (allow brackets for IPv6)
    let trimmed_host = payload.host.trim();
    if trimmed_host.is_empty() {
        return Err(PairingError::EmptyHost);
    }
    if payload.host.chars().any(|c| {
        c.is_whitespace()
            || ['/', '\\', '?', '#', '@'].contains(&c)
            || (c as u32) < 0x20
            || (c as u32) == 0x7F
    }) {
        return Err(PairingError::InvalidHost(format!(
            "Host '{}' contains forbidden characters (whitespace, delimiters, or control characters)",
            payload.host
        )));
    }
    if (!trimmed_host.starts_with('[') || !trimmed_host.ends_with(']'))
        && trimmed_host.contains(':')
    {
        return Err(PairingError::InvalidHost(format!(
            "Host '{}' contains forbidden colon delimiter outside IPv6 brackets",
            payload.host
        )));
    }

    // 6. Port: 1..=65535 (u16 is <= 65535, port 0 is invalid)
    if payload.port == 0 {
        return Err(PairingError::InvalidPort(0));
    }

    // 7. Scheme: "http" or "https" (case-insensitive)
    let scheme_lower = payload.scheme.to_lowercase();
    if scheme_lower != "http" && scheme_lower != "https" {
        return Err(PairingError::InvalidScheme(format!(
            "Invalid scheme '{}', must be 'http' or 'https'",
            payload.scheme
        )));
    }

    // 8. Nonce: Base64URL-encoded, decodes to exactly 16 bytes (128 bits)
    let trimmed_nonce = payload.nonce.trim();
    if trimmed_nonce.is_empty() {
        return Err(PairingError::InvalidNonce("Nonce cannot be empty".into()));
    }
    let decoded_nonce = URL_SAFE_NO_PAD
        .decode(trimmed_nonce.as_bytes())
        .or_else(|_| URL_SAFE.decode(trimmed_nonce.as_bytes()))
        .or_else(|_| STANDARD.decode(trimmed_nonce.as_bytes()))
        .map_err(|e| PairingError::InvalidNonce(format!("Nonce Base64 decode failed: {}", e)))?;

    if decoded_nonce.len() != CANONICAL_NONCE_BYTES {
        return Err(PairingError::InvalidNonce(format!(
            "Nonce length {} bytes is invalid, expected exactly {} bytes (128 bits)",
            decoded_nonce.len(),
            CANONICAL_NONCE_BYTES
        )));
    }

    // 9. Expires at: now - 30 <= expires_at
    let min_allowed_expiry = current_time.saturating_sub(MAX_CLOCK_SKEW_SECONDS);
    if payload.expires_at < min_allowed_expiry {
        return Err(PairingError::PayloadExpired {
            expires_at: payload.expires_at,
            now: current_time,
        });
    }

    Ok(())
}

pub fn create_pairing_payload(
    host_id: String,
    name: String,
    host: String,
    port: u16,
    scheme: String,
    ttl_seconds: u64,
) -> PairingPayloadV1 {
    let now = current_unix_timestamp();
    let ttl = ttl_seconds.clamp(MIN_TTL_SECONDS, MAX_TTL_SECONDS);
    let expires_at = now + ttl;
    let nonce = generate_nonce();

    PairingPayloadV1 {
        v: 1,
        payload_type: "hermes-pair".to_string(),
        host_id,
        name,
        host,
        port,
        scheme,
        expires_at,
        nonce,
        fingerprint: None,
    }
}

pub fn create_pairing_payload_v2(
    host_id: String,
    name: String,
    host: String,
    port: u16,
    scheme: String,
    ttl_seconds: u64,
    fingerprint: Option<String>,
) -> PairingPayloadV1 {
    let now = current_unix_timestamp();
    let ttl = ttl_seconds.clamp(MIN_TTL_SECONDS, MAX_TTL_SECONDS);
    let expires_at = now + ttl;
    let nonce = generate_nonce();

    PairingPayloadV1 {
        v: 2,
        payload_type: "hermes-pair".to_string(),
        host_id,
        name,
        host,
        port,
        scheme,
        expires_at,
        nonce,
        fingerprint,
    }
}

pub fn encode_pairing_uri(payload: &PairingPayloadV1) -> String {
    let json = serde_json::to_string(payload)
        .expect("Serialization of PairingPayloadV1 should never fail");
    let encoded = URL_SAFE_NO_PAD.encode(json.as_bytes());
    format!("hermes://pair?data={}", encoded)
}

pub fn decode_pairing_uri_at_time(
    uri: &str,
    current_time: u64,
) -> Result<PairingPayloadV1, PairingError> {
    if uri.len() > MAX_ENCODED_URI_BYTES {
        return Err(PairingError::PayloadTooLarge {
            size: uri.len(),
            max: MAX_ENCODED_URI_BYTES,
        });
    }

    let trimmed = uri.trim();
    let is_valid_prefix = trimmed.starts_with("hermes://pair?")
        || trimmed.starts_with("hermes:/pair?")
        || trimmed.eq_ignore_ascii_case("hermes://pair")
        || trimmed.eq_ignore_ascii_case("hermes:/pair");

    if !is_valid_prefix {
        if trimmed.starts_with("hermes:") || trimmed.starts_with("hermes://") {
            return Err(PairingError::InvalidUriFormat(uri.to_string()));
        } else {
            let scheme = trimmed.split_once(':').map(|(s, _)| s).unwrap_or("unknown");
            return Err(PairingError::InvalidUriScheme(scheme.to_string()));
        }
    }

    let query_part = match trimmed.split_once('?') {
        Some((_, q)) => q,
        None => return Err(PairingError::MissingDataParameter),
    };

    let mut data_str = None;
    for segment in query_part.split('&') {
        if segment.is_empty() {
            continue;
        }
        let (k_raw, v_raw) = match segment.split_once('=') {
            Some((k, v)) => (k, v),
            None => (segment, ""),
        };
        let key = url::form_urlencoded::parse(k_raw.as_bytes())
            .next()
            .map(|(k, _)| k.into_owned())
            .unwrap_or_default();
        if key == "data" {
            let val = url::form_urlencoded::parse(v_raw.as_bytes())
                .next()
                .map(|(v, _)| v.into_owned())
                .unwrap_or_default();
            data_str = Some(val);
            break;
        }
    }

    let data = data_str.ok_or(PairingError::MissingDataParameter)?;
    if data.is_empty() {
        return Err(PairingError::EmptyData);
    }

    let decoded_bytes = URL_SAFE_NO_PAD
        .decode(data.as_bytes())
        .or_else(|_| URL_SAFE.decode(data.as_bytes()))
        .or_else(|_| STANDARD.decode(data.as_bytes()))
        .map_err(|e| PairingError::Base64DecodeError(e.to_string()))?;

    if decoded_bytes.len() > MAX_DECODED_JSON_BYTES {
        return Err(PairingError::PayloadTooLarge {
            size: decoded_bytes.len(),
            max: MAX_DECODED_JSON_BYTES,
        });
    }

    let payload: PairingPayloadV1 = serde_json::from_slice(&decoded_bytes)
        .map_err(|e| PairingError::JsonDecodeError(e.to_string()))?;

    validate_payload(&payload, current_time)?;

    Ok(payload)
}

pub fn decode_pairing_uri(uri: &str) -> Result<PairingPayloadV1, PairingError> {
    let now = current_unix_timestamp();
    decode_pairing_uri_at_time(uri, now)
}
