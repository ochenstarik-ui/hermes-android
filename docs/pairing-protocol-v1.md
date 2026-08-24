# Hermes Pairing Protocol v1 (hermes-pair)

## 1. URI Format
The pairing URI is used to transmit connection information securely to the Hermes client, typically via QR codes or deep links.
* **Canonical Format**: `hermes://pair?data=<base64url_payload>`
* **Tolerated Formats**: Clients MUST also tolerate single slashes (e.g., `hermes:/pair?data=...`) to accommodate aggressive URL normalization applied by certain OS deep link handlers or third-party QR scanners.

## 2. Payload Fields & Types
The `data` query parameter contains a Base64 encoded JSON string representing the payload.

| Field | Type | Description |
|---|---|---|
| `v` | `u32` | Protocol version. Must be exactly `1`. |
| `type` | `string` | Payload type. Must be exactly `"hermes-pair"`. |
| `host_id` | `string` | A valid UUID (canonical string representation, 8-4-4-4-12) identifying the host server. |
| `name` | `string` | Human-readable string containing the name of the server/host. |
| `host` | `string` | The IP address (IPv4, or IPv6 enclosed in brackets e.g. `[::1]`) or a valid hostname of the server. |
| `port` | `u16` | TCP port number (1-65535). Cannot be `0`. |
| `scheme` | `string` | The HTTP scheme to use. Must be either `"http"` or `"https"`. |
| `expires_at` | `u64` | Expiration time as a Unix timestamp in seconds since epoch. |
| `nonce` | `string` | 16 bytes of random data, encoded as a Base64URL string (unpadded). Ensures QR code uniqueness and helps prevent replay attacks. |

## 3. Decisions on the 4 Divergences

1. **UUID Version (host_id)**: 
   The `host_id` MUST be validated as a legitimate UUID according to RFC 4122. Any valid UUID version (such as v4, v7, etc.) is accepted, provided it parses correctly as a standard 128-bit UUID. 
   *Justification*: Limiting strict validation to only v4 limits future upgrades (e.g., migrating to v7 for time-sorting), while enforcing any valid UUID check provides the required uniqueness and collision resistance.

2. **Base64 Encoding (data)**: 
   The canonical encoding for the `data` parameter (and the `nonce` inside) is **URL-safe Base64 without padding** (RFC 4648 Section 5). 
   *Tolerance*: Parsers MUST be robust and accept URL-safe Base64 with padding, as well as standard Base64 encoding. 
   *Justification*: This maximizes interoperability with various generation libraries and ecosystem tools that may apply padding or default standard Base64 despite requests to be URL-safe.

3. **Data Extraction (URL query parsing)**: 
   Parsers MUST use robust, standard URL query parameter extraction mechanisms (e.g., standard `URLDecoder` in Java/Kotlin, or `query_pairs()` in Rust). 
   They MUST handle extraneous query parameters gracefully by ignoring them, and strictly tolerate anomalous empty segments such as double ampersands `&&`.

4. **URI Scheme**: 
   The `hermes://pair` schema/host structure is strictly canonical.

## 4. Validation Rules & Error Classification

During parsing and validation, clients MUST return distinct typed errors corresponding to the following failure modes to allow for proper UX messaging or fallback behavior:

| Error Code | Description |
|---|---|
| `InvalidUriFormat` | The URI does not match `hermes://pair` or `hermes:/pair`. |
| `MissingDataParam` | The `data` query parameter is missing from the URI. |
| `Base64DecodeError` | The `data` string cannot be decoded as Base64 (corrupt). |
| `JsonSyntaxError` | The decoded string is not valid JSON. |
| `InvalidPayloadType` | The `type` field is missing or not `"hermes-pair"`. |
| `UnsupportedProtocolVersion` | The `v` field is not `1`. |
| `InvalidHostId` | The `host_id` is missing or not a valid UUID string. |
| `InvalidPort` | The `port` is missing, `0`, or out of valid `u16` range. |
| `InvalidScheme` | The `scheme` is missing, or not `"http"` or `"https"`. |
| `InvalidNonce` | The `nonce` is missing, incorrectly sized, or malformed. |
| `ExpiredPayload` | The `expires_at` timestamp is historically in the past. |
| `ClockSkewError` | The `expires_at` timestamp is excessively in the future or rejected due to extreme local clock skew anomalies. |

## 5. Nonce and Replay Prevention

The `nonce` field serves to ensure that every generated QR code payload string is unique, preventing predictable QR patterns. 
To prevent replay attacks (where an intercepted QR code is reused maliciously by a third party), clients and servers SHOULD implement a single-use tracking mechanism:
1. **Client-side cache**: Clients should record the `nonce` of successfully parsed and used pairing payloads.
2. **Rejection**: If a payload is presented containing an already-seen `nonce`, it must be rejected.
3. **Cache Eviction**: The cache entries only need to be retained until the `expires_at` timestamp of the payload, after which the basic `ExpiredPayload` check will naturally reject it, saving memory.
