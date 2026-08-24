# Contributing to Hermes Android

Thank you for your interest in contributing to **Hermes Android** and **Hermes Pair**!

This document provides guidelines and instructions for setting up your development environment, running tests and linters, and submitting pull requests.

---

## 🛠️ Development Setup & Prerequisites

### Android Client (`/`)
- **JDK**: Eclipse Temurin OpenJDK 17
- **Android SDK**: Android API 35 (compileSdk/targetSdk), API 26 (minSdk), Build-Tools 35.0.0
- **Gradle**: Wrapper provided (`./gradlew`)

### Hermes Pair Companion (`hermes-pair/`)
- **Rust Toolchain**: Stable Rust 1.80+ (`rustup default stable`)
- **Components**: `clippy`, `rustfmt`

---

## 🧪 Building & Running Verification Tests

Before submitting a Pull Request, all unit tests, linters, and builds must pass locally.

### 1. Android Verification Suite

Run all verification checks required by CI:

```bash
# Run Android Unit Tests
./gradlew --no-daemon testDebugUnitTest

# Run Android Lint
./gradlew --no-daemon lint

# Build Debug APK
./gradlew --no-daemon assembleDebug
```

On Windows (PowerShell):
```powershell
.\gradlew.bat --no-daemon testDebugUnitTest
.\gradlew.bat --no-daemon lint
.\gradlew.bat --no-daemon assembleDebug
```

### 2. Rust Companion Verification Suite (`hermes-pair`)

```bash
cd hermes-pair

# Run all Rust tests
cargo test --all-targets

# Run Cargo Clippy (must be zero warnings)
cargo clippy -- -D warnings

# Build release binary
cargo build --release
```

---

## 📋 Pull Request (PR) Guidelines

1. **Clean Diffs**: Ensure no temporary build files, `.apk`, `.exe`, or generated binaries are included in git commits.
2. **Never Commit Binaries**: Binaries belong in GitHub Releases produced by the CI/CD pipeline, never in git history.
3. **Keep Tests Green**: All CI checks (`android`, `rust`, `instrumented`) must pass.
4. **Version Catalog**: All Android dependencies must be declared in `gradle/libs.versions.toml`. Do not hardcode dependency strings in `build.gradle.kts`.
5. **Commit Conventions**: Use structured commit messages (e.g. `feat: ...`, `fix: ...`, `docs: ...`, `test: ...`, `ci: ...`, `refactor: ...`).

---

## 🔒 Security & Sensitive Data

- Never commit secrets, API keys, passwords, or personal credentials.
- Report security issues privately via [SECURITY.md](SECURITY.md).
