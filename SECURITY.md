# Security Policy

## Supported Versions

We release security patches for the latest versions of Hermes Android and Hermes Pair.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

The Hermes team takes the security of our application, user credentials, and network communications seriously.

If you believe you have found a security vulnerability in Hermes Android or Hermes Pair, please report it responsibly:

1. **Do not disclose the issue publicly** in GitHub Issues, Discussions, or pull requests.
2. **Submit a report** via GitHub Private Vulnerability Reporting at [GitHub Security Advisories](https://github.com/ochenstarik-ui/hermes-android/security/advisories/new) or contact repository maintainers directly.
3. **Include details**:
   - Description of the vulnerability.
   - Steps to reproduce or proof-of-concept (PoC).
   - Affected components (`hermes-android` client, `hermes-pair` helper, token vault, or network layer).
   - Potential impact.

### Response Timeline

- **Initial Triage**: We aim to acknowledge receipt of vulnerability reports within **48 hours**.
- **Assessment & Fix**: We will provide a status update within **7 days** with an assessment of the vulnerability and expected remediation timeline.
- **Disclosure**: A security advisory and public release notes will be coordinated once a patch is released and tested.

## Security Practices

- Hermes Android stores host tokens in Android Keystore / EncryptedSharedPreferences with host isolation.
- Network communications support HTTPS and WSS with token-based authentication and ticket exchange.
- `hermes-pair` generates cryptographically random nonces (16-byte CSPRNG) with strict TTL expiration for pairing QR codes.
