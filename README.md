# Hermes Android Native Remote Client

A production-grade, native Android client application for **Hermes**, implementing Protocol & Architecture Contract v1 with **Multi-Hermes Connection Manager** and **Unified Sessions**.

Built with **Kotlin**, **Jetpack Compose (Material 3)**, **Coroutines**, **Room Database**, **OkHttp**, and **Android Keystore (EncryptedSharedPreferences)**.

---

## 🌟 Architecture Overview

```
   ┌─────────────────────────────────────────────────────────────────────────┐
   │                          Hermes Android Client                          │
   │  ┌───────────────────────────────────────────────────────────────────┐  │
   │  │                      Jetpack Compose UI (M3)                      │  │
   │  │   • Multi-Host Switcher • Unified Sessions • Attributed Chat      │  │
   │  └─────────────────────────────────┬─────────────────────────────────┘  │
   │                                    │ StateFlow / Actions                │
   │  ┌─────────────────────────────────▼─────────────────────────────────┐  │
   │  │                   Unified Session Repository                      │  │
   │  │   • Logical Unified Sessions   • Context Synchronization Delta    │  │
   │  │   • Host-Tagged Event Routing  • Local Persistence (Room DB)      │  │
   │  └──────────────────┬──────────────────────────────┬─────────────────┘  │
   │                     │                              │                    │
   │  ┌──────────────────▼──────────────────┐  ┌────────▼─────────────────┐  │
   │  │      Hermes Connection Manager      │  │   Encrypted Token Vault  │  │
   │  │   • Map<HostId, HostRuntime>        │  │   (Host-Scoped Keystore) │  │
   │  └───────┬─────────────────────────┬───┘  └──────────────────────────┘  │
   │          │                         │                                    │
   │  ┌───────▼─────────────┐   ┌───────▼─────────────┐                      │
   │  │   Host #1 Runtime   │   │   Host #2 Runtime   │                      │
   │  │  (OkHttp WS + REST) │   │  (OkHttp WS + REST) │                      │
   │  └───────┬─────────────┘   └───────┬─────────────┘                      │
   └──────────┼─────────────────────────┼────────────────────────────────────┘
              │                         │
              ▼                         ▼
   ┌──────────────────────┐  ┌──────────────────────┐
   │    Hermes Host #1    │  │    Hermes Host #2    │
   │   (Windows Office)   │  │    (Linux Server)    │
   │   `hermes serve`     │  │   `hermes serve`     │
   └──────────────────────┘  └──────────────────────┘
```

---

## 🚀 Key Multi-Host Features

1. **Multi-Hermes Connection Manager**:
   - Save and manage multiple independent Hermes installations (e.g. Workstation, Linux Server, Cloud VM).
   - Independent WebSocket connections, concurrent state management, and isolated reconnect loops.
   - Individual host health badges: `Online`, `Connecting`, `Offline`, `Auth Expired`.

2. **Unified Sessions & Context Synchronization**:
   - Create one logical conversation (`UnifiedSession`) that spans multiple physical Hermes hosts.
   - Seamlessly switch active execution hosts mid-conversation via the top-bar dropdown.
   - **Delta Context Sync**: Injects conversation history and task context to newly attached hosts automatically without full-history re-transmission or secret leakage.
   - **Host Attribution**: Every response bubble, tool card, and thinking trace displays its originating host badge (e.g. `[Office PC]`, `[Linux Server]`).
   - **Non-Blocking Host Switching**: If Host #1 is executing a long tool or computation and you switch to Host #2, Host #1 completes its work in the background and commits results into the shared timeline.

3. **Isolated Host Security & Approvals**:
   - Host-scoped credentials stored securely in Android Keystore (`hostId -> tokens`).
   - **Host-Targeted Approvals & Clarifications**: Dangerous command approvals (`approval.request`) and sudo prompts route back strictly to the exact host runtime and native session that emitted them.

4. **Local Persistence (Room DB)**:
   - Full offline caching for `UnifiedSession`, `HostSessionBinding`, and `UnifiedMessage`.
   - Raw native session browser for inspecting individual host histories.

---

## 🖥️ Hermes Host Setup

### Windows Host

```powershell
hermes serve --host 0.0.0.0 --port 9119
```

With OAuth / GitHub Auth:
```powershell
$env:HERMES_AUTH_REQUIRED="true"
$env:HERMES_AUTH_PROVIDERS="github"
$env:HERMES_AUTH_GITHUB_CLIENT_ID="<your_client_id>"
$env:HERMES_AUTH_GITHUB_CLIENT_SECRET="<your_client_secret>"
hermes serve --host 0.0.0.0 --port 9119
```

### Linux Host

```bash
export HERMES_AUTH_REQUIRED="true"
export HERMES_AUTH_PROVIDERS="github"
export HERMES_AUTH_GITHUB_CLIENT_ID="<your_client_id>"
export HERMES_AUTH_GITHUB_CLIENT_SECRET="<your_client_secret>"

hermes serve --host 0.0.0.0 --port 9119
```

---

## 🧪 Testing & Verification

Run the full automated test suite:

```powershell
.\gradlew.bat test
```

Run Android Lint:

```powershell
.\gradlew.bat lint
```

Assemble Debug APK:

```powershell
.\gradlew.bat assembleDebug
```

---

## 📱 Instant QR Onboarding — Hermes Pair (`hermes-pair/`)

Inside the `hermes-pair/` directory is the cross-platform desktop companion application written in Rust. It runs on Windows and Linux to auto-discover your local IP and generate a secure QR code for instant onboarding with Hermes Android.

### 1. Download Prebuilt Binaries (GitHub Releases)

Download prebuilt binaries and `SHA256SUMS.txt` from the latest [GitHub Releases](https://github.com/ochenstarik-ui/hermes-android/releases).

#### Verifying SHA-256 Checksums:

- **Windows (PowerShell)**:
  ```powershell
  Get-FileHash .\hermes-pair-windows-x86_64.exe -Algorithm SHA256
  # Compare the resulting hash with SHA256SUMS.txt
  ```

- **Linux**:
  ```bash
  sha256sum -c SHA256SUMS.txt
  # or verify directly:
  sha256sum hermes-pair-linux-x86_64
  ```

### 2. Running Hermes Pair

#### Windows (GUI or CLI):
```powershell
# Launch GUI window
.\hermes-pair-windows-x86_64.exe

# Terminal QR output
.\hermes-pair-windows-x86_64.exe qr --port 9119
```

#### Linux (GUI or Headless Server):
```bash
chmod +x hermes-pair-linux-x86_64

# Launch GUI window
./hermes-pair-linux-x86_64

# Headless / Terminal QR
./hermes-pair-linux-x86_64 --terminal --port 9119
```

### 3. Building Hermes Pair from Source:
```bash
cd hermes-pair
cargo test
cargo build --release
```

The compiled binaries will be located at:
- **Windows**: `hermes-pair/target/release/hermes-pair.exe`
- **Linux**: `hermes-pair/target/release/hermes-pair`

