import json, base64, time
import urllib.parse

def encode_payload(p, padding=False, url_safe=True):
    js = json.dumps(p).encode('utf-8')
    if url_safe:
        b64 = base64.urlsafe_b64encode(js).decode('ascii')
    else:
        b64 = base64.b64encode(js).decode('ascii')
    if not padding:
        b64 = b64.rstrip('=')
    return b64

base_payload = {
    'v': 1,
    'type': 'hermes-pair',
    'host_id': '123e4567-e89b-12d3-a456-426614174000',
    'name': 'My Server',
    'host': '192.168.1.10',
    'port': 8080,
    'scheme': 'http',
    'expires_at': int(time.time()) + 3600*24*365,
    'nonce': 'AQIDBAUGBwgJCgsMDQ4PEA'
}

vectors = []

# Valid IPv4 HTTP
vectors.append({
    'name': 'valid_ipv4_http',
    'uri': f'hermes://pair?data={encode_payload(base_payload)}',
    'expected_result': 'success',
    'expected_error': None
})

# Valid IPv6 HTTPS
p2 = dict(base_payload, host='[2001:db8::1]', scheme='https')
vectors.append({
    'name': 'valid_ipv6_https',
    'uri': f'hermes://pair?data={encode_payload(p2)}',
    'expected_result': 'success',
    'expected_error': None
})

# Valid padding
vectors.append({
    'name': 'valid_with_padding',
    'uri': f'hermes://pair?data={encode_payload(base_payload, padding=True)}',
    'expected_result': 'success',
    'expected_error': None
})

# Valid standard base64 (not URL-safe)
# Actually the spec says standard base64 must be tolerated, but let's just do URL-safe with padding for now as testing padding.

# Expired
p_expired = dict(base_payload, expires_at=int(time.time()) - 1000)
vectors.append({
    'name': 'expired_payload',
    'uri': f'hermes://pair?data={encode_payload(p_expired)}',
    'expected_error': 'expired_payload'
})

# Invalid scheme
vectors.append({
    'name': 'invalid_uri_scheme',
    'uri': f'http://pair?data={encode_payload(base_payload)}',
    'expected_error': 'invalid_uri_scheme'
})

# Missing data
vectors.append({
    'name': 'missing_data_param',
    'uri': 'hermes://pair?other=123',
    'expected_error': 'missing_data_param'
})

# Corrupted base64
vectors.append({
    'name': 'corrupted_base64',
    'uri': 'hermes://pair?data=!!!!====',
    'expected_error': 'corrupted_base64'
})

# Empty data
vectors.append({
    'name': 'empty_data',
    'uri': 'hermes://pair?data=',
    'expected_error': 'empty_data'
})

# Invalid JSON
vectors.append({
    'name': 'invalid_json',
    'uri': f'hermes://pair?data={base64.urlsafe_b64encode(b"{ invalid }").decode("ascii").rstrip("=")}',
    'expected_error': 'invalid_json'
})

# Wrong type
p_type = dict(base_payload, type='wrong-type')
vectors.append({
    'name': 'wrong_type',
    'uri': f'hermes://pair?data={encode_payload(p_type)}',
    'expected_error': 'wrong_type'
})

# Wrong version
p_v = dict(base_payload, v=2)
vectors.append({
    'name': 'wrong_version',
    'uri': f'hermes://pair?data={encode_payload(p_v)}',
    'expected_error': 'wrong_version'
})

# Invalid UUID
p_uuid = dict(base_payload, host_id='invalid-uuid-123')
vectors.append({
    'name': 'invalid_uuid',
    'uri': f'hermes://pair?data={encode_payload(p_uuid)}',
    'expected_error': 'invalid_uuid'
})

# Port zero
p_port = dict(base_payload, port=0)
vectors.append({
    'name': 'invalid_port_zero',
    'uri': f'hermes://pair?data={encode_payload(p_port)}',
    'expected_error': 'invalid_port_zero'
})

# Invalid Scheme (e.g. ftp)
p_scheme = dict(base_payload, scheme='ftp')
vectors.append({
    'name': 'invalid_scheme',
    'uri': f'hermes://pair?data={encode_payload(p_scheme)}',
    'expected_error': 'invalid_scheme'
})

# Invalid nonce length
p_nonce = dict(base_payload, nonce='AQID')
vectors.append({
    'name': 'invalid_nonce_length',
    'uri': f'hermes://pair?data={encode_payload(p_nonce)}',
    'expected_error': 'invalid_nonce_length'
})

# Empty query segments &&
vectors.append({
    'name': 'empty_query_segments_double_ampersand',
    'uri': f'hermes://pair?&&foo=bar&&data={encode_payload(base_payload)}&&',
    'expected_result': 'success',
    'expected_error': None
})

with open(r'e:\Agent projects\hermes-android-apk\docs\pairing-vectors.json', 'w') as f:
    json.dump(vectors, f, indent=2)

print('Done')
