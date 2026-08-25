use hermes_pair::pairing::{decode_pairing_uri_at_time, PairingError};
use serde::Deserialize;
use std::fs;
use std::path::Path;

#[derive(Debug, Deserialize)]
struct PairingVector {
    name: String,
    uri: String,
    expected_result: Option<String>,
    expected_error: Option<String>,
}

fn load_vectors() -> Vec<PairingVector> {
    let possible_paths = [
        Path::new("docs/pairing-vectors.json"),
        Path::new("../docs/pairing-vectors.json"),
        Path::new("../../docs/pairing-vectors.json"),
    ];

    for path in &possible_paths {
        if path.exists() {
            let content = fs::read_to_string(path).expect("Failed to read pairing-vectors.json");
            return serde_json::from_str(&content).expect("Failed to parse pairing-vectors.json");
        }
    }

    panic!("Could not find docs/pairing-vectors.json in paths: {:?}", possible_paths);
}

fn error_to_code(err: &PairingError) -> &'static str {
    match err {
        PairingError::InvalidUriScheme(_) => "invalid_uri_scheme",
        PairingError::InvalidUriFormat(_) => "invalid_uri_scheme",
        PairingError::MissingDataParameter => "missing_data_param",
        PairingError::EmptyData => "empty_data",
        PairingError::Base64DecodeError(_) => "corrupted_base64",
        PairingError::JsonDecodeError(_) => "invalid_json",
        PairingError::InvalidPayloadType(_) => "wrong_type",
        PairingError::UnsupportedVersion(_) => "wrong_version",
        PairingError::InvalidHostId(_) => "invalid_uuid",
        PairingError::InvalidPort(_) => "invalid_port_zero",
        PairingError::InvalidScheme(_) => "invalid_scheme",
        PairingError::InvalidNonce(_) => "invalid_nonce_length",
        PairingError::InvalidFingerprint(_) => "invalid_fingerprint",
        PairingError::PayloadExpired { .. } => "expired_payload",
        PairingError::TtlExceedsMaximum { .. } => "ttl_exceeds_maximum",
        PairingError::InvalidName(_) => "invalid_name",
        PairingError::EmptyHost => "empty_host",
        PairingError::InvalidHost(_) => "invalid_host",
        PairingError::PayloadTooLarge { .. } => "payload_too_large",
        PairingError::InvalidTtl { .. } => "invalid_ttl",
    }
}

#[test]
fn test_all_pairing_vectors() {
    let vectors = load_vectors();
    assert!(!vectors.is_empty(), "Vectors file must not be empty");

    // Static test vector timestamp is 1819124926 (approx 2027), Vector 4 expired is 1787587926.
    // Use test time 1800000000.
    let test_now = 1800000000;

    let mut failures = Vec::new();

    for vector in &vectors {
        let result = decode_pairing_uri_at_time(&vector.uri, test_now);

        if vector.expected_result.as_deref() == Some("success") {
            if let Err(e) = result {
                failures.push(format!(
                    "[{}] Expected SUCCESS, but got error: {:?} ({})",
                    vector.name, e, e
                ));
            }
        } else if let Some(ref exp_err) = vector.expected_error {
            match result {
                Ok(p) => {
                    failures.push(format!(
                        "[{}] Expected FAILURE '{}', but got SUCCESS: {:?}",
                        vector.name, exp_err, p
                    ));
                }
                Err(ref e) => {
                    let actual_code = error_to_code(e);
                    if actual_code != exp_err {
                        failures.push(format!(
                            "[{}] Expected error code '{}', but got '{}' ({:?})",
                            vector.name, exp_err, actual_code, e
                        ));
                    }
                }
            }
        }
    }

    if !failures.is_empty() {
        panic!(
            "Pairing vector test failures ({}/{}):\n{}",
            failures.len(),
            vectors.len(),
            failures.join("\n")
        );
    }
}
