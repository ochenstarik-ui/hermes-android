# Hermes Android Native Remote Client

A production-grade, native Android client application for **Hermes**, implementing Protocol & Architecture Contract v1.

Built with **Kotlin**, **Jetpack Compose (Material 3)**, **Coroutines**, **OkHttp**, and **Android Keystore (EncryptedSharedPreferences)**.

---

## 🌟 Architecture Overview

```
   ┌─────────────────────────────────────────────────────────┐
   │                  Hermes Android Client                  │
   │  ┌───────────────────────────────────────────────────┐  │
   │  │              Jetpack Compose UI (M3)              │  │
   │  │   • Connections  • Sessions  • Chat & Approvals   │  │
   │  └─────────────────────────┬─────────────────────────┘  │
   │                            │ StateFlow / Actions         │
   │  ┌─────────────────────────▼─────────────────────────┐  │
   │  │                Hermes Gateway Layer               │  │
   │  │   • Reconnection Loop with Exponential Backoff    │  │
   │  │   • Session State Reconciliation                  │  │
   │  │   • Event Stream Dispatcher                       │  │
   │  └──────────┬──────────────────────────┬─────────────┘  │
   │             │ JSON-RPC / Ticket        │ PKCE Auth       │
   │  ┌──────────▼──────────┐    ┌──────────▼──────────────┐  │
   │  │   OkHttp WebSocket  │    │ Loopback Auth Server    │  │
   │  │   (Single-Use Auth) │    │ (127.0.0.1:<port>)      │  │
   │  └──────────┬──────────┘    └──────────┬──────────────┘  │
   └─────────────┼──────────────────────────┼─────────────────┘
                 │                          │
                 ▼                          ▼
   ┌─────────────────────────────────────────────────────────┐
   │                      Hermes Host                        │
   │                    (`hermes serve`)                     │
   │                                                         │
   │   • `GET /api/status`           • `GET /auth/native/...`│
   │   • `POST /api/auth/ws-ticket`  • `WS /ws?ticket=...`   │
   └─────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started & Host Setup

### 1. Windows Host Setup

Run the Hermes server binding to all interfaces (or your LAN / Tailscale IP):

```powershell
hermes serve --host 0.0.0.0 --port 9119
```

To configure GitHub authentication:
```powershell
$env:HERMES_AUTH_REQUIRED="true"
$env:HERMES_AUTH_PROVIDERS="github"
$env:HERMES_AUTH_GITHUB_CLIENT_ID="<your_client_id>"
$env:HERMES_AUTH_GITHUB_CLIENT_SECRET="<your_client_secret>"
hermes serve --host 0.0.0.0 --port 9119
```

### 2. Linux Host Setup

```bash
export HERMES_AUTH_REQUIRED="true"
export HERMES_AUTH_PROVIDERS="github"
export HERMES_AUTH_GITHUB_CLIENT_ID="<your_client_id>"
export HERMES_AUTH_GITHUB_CLIENT_SECRET="<your_client_secret>"

hermes serve --host 0.0.0.0 --port 9119
```

---

## 📱 Android Client Features

1. **Host Connection Manager**:
   - Save multiple Hermes host endpoints.
   - Live endpoint verification (`GET /api/status`).
   - Cleartext HTTP toggling with explicit security warning badges for local development.

2. **Native PKCE Authentication**:
   - RFC 7636 & RFC 8252 compliant PKCE loopback authentication on `127.0.0.1:<ephemeral_port>`.
   - Single-use WebSocket tickets with 30s TTL.
   - Credentials securely stored via Android Keystore & `EncryptedSharedPreferences`. Zero token logging.

3. **Session Management**:
   - Resume durable sessions (`DurableSessionId`) or create new sessions.
   - Dynamic reconciliation across network disconnects.

4. **Real-time Chat Experience**:
   - Streaming token deltas (`message.delta`).
   - Collapsible reasoning & chain-of-thought section (`thinking.delta`).
   - Real-time tool execution tracking cards (`tool.start`, `tool.progress`, `tool.complete`).
   - **Interactive Approvals**: Immediate in-stream approval card for dangerous commands (`Allow Once`, `Allow Always`, `Deny`).
   - **Clarifications & Sudo**: Masked dialogs for `sudo.request`, `secret.request`, and `clarify.request`.
   - Interrupt / Stop execution control.

---

## 🔒 Security Best Practices for Remote Access

- **Do NOT expose cleartext HTTP directly to the public internet.**
- **Recommended**: Connect via **Tailscale**, **WireGuard**, or a TLS Reverse Proxy (Caddy / Nginx) with HTTPS & WSS.
- The Android client strictly enforces `usesCleartextTraffic="false"` at the manifest level by default.

---

## 🧪 Testing & Verification

Run all unit tests via Gradle:

```powershell
.\gradlew testDebugUnitTest
```

Build the release or debug APK:

```powershell
.\gradlew assembleDebug
```
