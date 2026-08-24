use hermes_pair::pairing::{create_pairing_payload, decode_pairing_uri_at_time, encode_pairing_uri};
use uuid::Uuid;

#[test]
fn test_qr_payload_stability_within_ttl() {
    let host_id = Uuid::new_v4().to_string();
    let name = "Test-Rig".to_string();
    let host = "192.168.1.10".to_string();
    let port = 9119;
    let scheme = "http".to_string();
    let ttl = 120;

    let payload = create_pairing_payload(
        host_id.clone(),
        name.clone(),
        host.clone(),
        port,
        scheme.clone(),
        ttl,
    );

    let uri1 = encode_pairing_uri(&payload);
    let uri2 = encode_pairing_uri(&payload);

    // Encoding same payload multiple times must produce identical URI
    assert_eq!(uri1, uri2);

    let start_time = payload.expires_at - ttl;

    // Verify URI decodes successfully throughout TTL window
    for delta in [0, 10, 30, 60, 100, 119] {
        let check_time = start_time + delta;
        let decoded = decode_pairing_uri_at_time(&uri1, check_time)
            .unwrap_or_else(|e| panic!("Failed to decode at delta {}: {:?}", delta, e));
        assert_eq!(decoded.nonce, payload.nonce);
        assert_eq!(decoded.host_id, host_id);
        assert_eq!(decoded.expires_at, payload.expires_at);
        assert_eq!(decoded.host, host);
        assert_eq!(decoded.port, port);
    }

    // Verify expiration after TTL
    let expired_time = payload.expires_at + 31;
    let expired_res = decode_pairing_uri_at_time(&uri1, expired_time);
    assert!(expired_res.is_err());
}
