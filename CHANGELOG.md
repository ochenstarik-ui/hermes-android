# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Version Catalog**: Centralized dependency management via `gradle/libs.versions.toml`.
- **Dependabot**: Automated weekly dependency update checks for Gradle and Cargo.
- **CI Release Automation**: Automated GitHub Actions job building Windows (`hermes-pair-windows-x86_64.exe`) and Linux (`hermes-pair-linux-x86_64`) binaries on tag push (`v*`) with automated `SHA256SUMS.txt` generation.
- **Repository Documentation**: Added `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, and `LICENSE` tracking.
- **Gradle Optimizations**: Parallel execution, build caching, 4GB JVM heap, and non-final resource IDs in `gradle.properties`.

### Changed
- **Binary Hygiene**: Removed tracked binary distribution files (`hermes-pair/dist/**`) from git repository tracking; updated `.gitignore` and `README.md` to instruct downloading from GitHub Releases or building from source.

## [0.1.0] - 2026-08-24

### Added
- **Task 01 (Transport & LAN Reachability)**:
  - Multi-host connection management with independent WebSocket reconnect loops.
  - Automatic LAN IPv4 discovery and host health probing (`/api/status`).
  - Auth token and WebSocket ticket exchange protocol (`/auth/native/token`).
- **Task 02 (Persistence Integrity & Timeline Ordering)**:
  - Room database persistence for `UnifiedSession`, `HostSessionBinding`, and `UnifiedMessage`.
  - Monotonic chronological timeline ordering (`createdAt ASC, id ASC`).
  - Defensive data copying and atomic transaction handling in repositories and DAOs.
- **Task 03 (Critical UX & Lifecycle Scoping)**:
  - Compose UI state scoping with lifecycle-aware subscriptions.
  - CameraX QR scanning with lifecycle binding, runtime permission handling, and camera resource release.
  - Host-targeted approval and confirmation dialog routing.
- **Task 04 (CI Workflow & Test Harness)**:
  - GitHub Actions CI pipeline running Android unit tests, lint, assemble, Rust cargo test/clippy, and emulator instrumented tests.
  - Thread-safe fake DAOs (`FakeUnifiedSessionDao`, `FakeHostDao`) with lock synchronization.
  - Deterministic test synchronization replacing sleep-based polling.
  - E2E contract scenario test verifying end-to-end multi-host messaging, tool calling, and approval lifecycle.
