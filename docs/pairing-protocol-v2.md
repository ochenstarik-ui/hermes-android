# Hermes Pairing Protocol v2 (hermes-pair)

## 1. Overview & Motivation
Hermes Pairing Protocol v2 enhances local network security and reachability without compromising Android's strict network security policy (`cleartextTrafficPermitted="false"`).
By pairing over HTTPS and pinning the self-signed or CA-signed certificate's SHA-256 fingerprint, devices can establish secure, encrypted LAN connections without exposing arbitrary local IP addresses to unencrypted cleartext traffic.

## 2. URI Format
* **Canonical Format**: `hermes://pair?data=<base64url_payload>`
* **Tolerated Formats**: `hermes:/pair?data=...` (tolerates deep link normalization).

## 3. Payload Fields & Types
The `data` query parameter contains a Base64-encoded JSON object representing the pairing payload.

| Field | Type | Required | Description |
|---|---|---|---|
| `v` | `u32` | Yes | Protocol version. Must be `2` (or `1` for legacy payloads). |
| `type` | `string` | Yes | Payload type. Must be exactly `"hermes-pair"`. |
| `host_id` | `string` | Yes | A valid UUID (canonical string representation RFC 4122) identifying the host server. |
| `name` | `string` | Yes | Human-readable string containing the display name of the server/host (1..=128 chars, no control chars). |
| `host` | `string` | Yes | The IP address (IPv4, or bracketed IPv6 `[::1]`) or hostname of the server. |
| `port` | `u16` | Yes | TCP port number (1..=65535). Cannot be `0`. |
| `scheme` | `string` | Yes | HTTP scheme to use. Default is `"https"` in v2. Must be `"http"` or `"https"`. |
| `expires_at` | `u64` | Yes | Expiration Unix timestamp in seconds since epoch. |
| `nonce` | `string` | Yes | 16 bytes random data, encoded as Base64URL (unpadded). |
| `fingerprint` | `string` | Optional | SHA-256 fingerprint of the host's TLS certificate (hex-encoded, optionally with colons/spaces or `SHA256:` prefix). Used for TLS pinning on self-signed certificates. |

## 4. Certificate Fingerprint Normalization & Verification
1. **Format**: A 64-character hexadecimal SHA-256 digest of the DER-encoded X.509 server certificate.
2. **Normalization**: Clients strip optional prefixes (`SHA256:`, `SHA-256:`), colons `:`, spaces, and dashes `-`, converting all hex digits to uppercase.
3. **Pinning (`TlsFingerprintTrust`)**:
   - When `fingerprint` is provided, the client establishes TLS connections using a custom `X509TrustManager` that compares the SHA-256 fingerprint of the peer certificate against the pinned fingerprint.
   - If the certificate fingerprint matches, the TLS handshake succeeds even with self-signed certificates.
   - If the fingerprint does not match or cannot be verified, the connection fails immediately with `SSLPeerUnverifiedException` / `CertificateException`.

## 5. Backward Compatibility (v1 vs v2)
* **Clients**: All Hermes Android clients supporting Protocol v2 MUST accept both `v: 1` and `v: 2` payloads.
* **Legacy Hosts**: Hosts emitting v1 payloads (`v: 1`, without `fingerprint`, defaulting to HTTP or HTTPS) continue to be accepted.
* **Migration**: When a client pairs with an updated host (providing HTTPS + fingerprint), the host record is updated seamlessly in local storage (`HermesHost.certificateFingerprint`), upgrading previous plaintext or unpinned connections.

## 6. Validation Rules & Errors
| Error Code | Description |
|---|---|
| `InvalidUriFormat` / `invalid_uri_scheme` | URI does not match `hermes://pair` or `hermes:/pair`. |
| `MissingDataParam` / `missing_data_param` | `data` query parameter is missing. |
| `EmptyData` / `empty_data` | `data` query parameter is empty. |
| `Base64DecodeError` / `corrupted_base64` | Failed to decode Base64 payload. |
| `JsonSyntaxError` / `invalid_json` | JSON syntax error in decoded payload. |
| `InvalidPayloadType` / `wrong_type` | `type` is not `"hermes-pair"`. |
| `UnsupportedProtocolVersion` / `wrong_version` | `v` is neither `1` nor `2`. |
| `InvalidHostId` / `invalid_uuid` | `host_id` is not a valid UUID string. |
| `InvalidName` / `invalid_name` | Host name is empty, too long (>128 chars), or contains control characters. |
| `EmptyHost` / `empty_host` | Host address is empty. |
| `InvalidHost` / `invalid_host` | Host contains forbidden delimiters, whitespace, or misplaced colons. |
| `InvalidPort` / `invalid_port_zero` | Port is 0 or outside valid range (1..=65535). |
| `InvalidScheme` / `invalid_scheme` | Scheme is not `"http"` or `"https"`. |
| `InvalidNonce` / `invalid_nonce_length` | Nonce is missing, malformed, or does not decode to 16 bytes. |
| `InvalidFingerprint` / `invalid_fingerprint` | Fingerprint is malformed (not 64 hex characters). |
| `ExpiredPayload` / `expired_payload` | Payload expiry timestamp is in the past (>30s clock skew tolerance). |
