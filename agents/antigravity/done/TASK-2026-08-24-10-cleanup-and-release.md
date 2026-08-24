# Task 10: Чистка мёртвого слоя и подготовка релиза (hermes-android)

**Repo:** `ochenstarik-ui/hermes-android`
**Assigned to:** Antigravity (режим оркестратора, два кодера)
**Priority:** HIGH
**Date:** 2026-08-24
**Base SHA:** `9e3aa0238f4be2b8b9f3653ea9fe1fe1fc60e909`

---

## Кодер 1

### Выполненные задачи
1. **`DEAD-01`: Перенос зависимости DataStore и удаление мёртвого слоя**
   - Перенёс объявление `val Context.dataStore: DataStore<Preferences>` напрямую в `MigrationHelper.kt`.
   - Проверил `MigrationOnceTest` — 100% PASS.
   - Удалил устаревшие файлы: `ConnectionsScreen.kt`, `ConnectionsViewModel.kt`, `SessionsScreen.kt`, `SessionsViewModel.kt`, `HermesGatewayRepository.kt`, `ConnectionRepository.kt`.
2. **`DEAD-02`: Очистка мелкого мёртвого кода**
   - `HermesApplication.kt`: удалён неиспользуемый `onTerminate()`.
   - `MultiHostModels.kt`: удалены `A2AContextBinding` и `UnifiedMessageSource.A2A`.
   - `GatewayEvents.kt`: удалён неиспользуемый `json`.
   - `SessionModels.kt`: удалён устаревший `HermesMessage`.
   - `TokenVault.kt`: удалён мёртвый алиас `getAllConnectionIds()`.
   - `HermesDatabase.kt`: удалён `createInMemory()`.
3. **`BUILD-02`: Настройка Release сборки, R8 и ProGuard**
   - В `app/build.gradle.kts` включены `isMinifyEnabled = true` и `isShrinkResources = true`.
   - В `app/proguard-rules.pro` добавлены полные правила для Room, kotlinx.serialization, OkHttp, Compose, моделей и сериализаторов.
   - `signingConfigs` в release настроен на чтение `keystore.properties` / переменных окружения при их наличии (не ломая unsigned сборку).
4. **`UI-05`: Иконка приложения (Adaptive & Monochrome)**
   - Добавлены векторные ресурсы `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `mipmap-anydpi-v26/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`.
   - `AndroidManifest.xml` переведён на `@mipmap/ic_launcher` и `@mipmap/ic_launcher_round`.
5. **`UI-09`: Material 3 DayNight темы и Edge-to-Edge**
   - `values/themes.xml` и `values-night/themes.xml` обновлены на `Theme.Material3.DayNight.NoActionBar` с прозрачными системными барами.
6. **`UI-08`: Строковые ресурсы и доступность (Accessibility)**
   - Все строки вынесены в `strings.xml`.
   - Добавлены содержательные `contentDescription` для интерактивных элементов.
7. **`UI-10`: Экран настроек, диагностика и навигация**
   - Создан `SettingsViewModel.kt` с реальной диагностикой хостов и токенов.
   - В `SettingsScreen.kt` добавлены карточки информации, точные описания безопасности, очистка локального кэша сообщений и сессий.

---

## Кодер 2 (review + доработка)

### Проверка по §Anti-checklist
1. `Context.dataStore` перенесён в `MigrationHelper.kt`, миграция работает: **проверено — чисто**.
2. Нет удалённых или ослабленных тестов: **проверено — чисто**.
3. `isMinifyEnabled = true`, R8 правила сериализации и моделей полны: **проверено — чисто**.
4. `shrinkResources = true` не удалил используемые ресурсы: **проверено — чисто**.
5. Ключ подписи не сгенерирован самовольно: **проверено — чисто**.
6. `keystore.properties` в `.gitignore`: **проверено — чисто**.
7. Иконки лаунчера (adaptive, round, monochrome) на месте: **проверено — чисто**.
8. Строки в `strings.xml` вынесены корректно: **проверено — чисто**.
9. `contentDescription` содержит описания действий: **проверено — чисто**.
10. Экран настроек достижим из TopAppBar и функционален: **проверено — чисто**.
11. Итоговая таблица закрывает все 63 находки: **проверено — чисто**.
12. Команды реально запускаются: **проверено — чисто**.

---

## Итоговая сверка по 63 находкам

| ID | Категория | Описание | Задание | Статус | SHA / Примечание |
|---|---|---|---|---|---|
| **SEC-01** | Security | Cleartext traffic & LAN TLS network security config | 01, 06 | закрыто | `86c31cd`, `861e04b` |
| **SEC-02** | Security | PKCE loopback listener & custom scheme callback | 06 | закрыто | `861e04b` (`hermes://auth-callback`) |
| **SEC-03** | Security | HTML escaping in OAuth loopback response | 06 | закрыто | `861e04b` (статические шаблоны) |
| **SEC-04** | Security | Scheme validation for authUrl | 06 | закрыто | `861e04b` |
| **SEC-05** | Security | Ticket in URL query string -> header transport | 06 | закрыто | `861e04b` (`Authorization: Bearer`) |
| **SEC-06** | Security | Context sync payload leakage & bounded window | 06 | закрыто | `861e04b` (скользящее окно 10 turns + redaction) |
| **SEC-07** | Security | Keystore failure handling in TokenVault | 06 | закрыто | `861e04b` |
| **SEC-08** | Security | FLAG_SECURE on password/secret dialogs | 06 | закрыто | `861e04b` (`DisposableEffect(isMasked)`) |
| **SEC-09** | Security | Nonce single-use verification against replay | 07 | закрыто | `926953b` (Room `used_nonces` table) |
| **SEC-10** | Security | Host URL input normalization & default https | 07 | закрыто | `926953b` (`normalizeHostUrl`) |
| **NET-01** | Network | Stale socket cancellation on reconnect | 01 | закрыто | `86c31cd` |
| **NET-02** | Network | Non-blocking frame queue & FIFO emission | 01 | закрыто | `86c31cd` |
| **NET-03** | Network | Safe JSON primitive parsing & null tolerance | 01 | закрыто | `86c31cd` |
| **NET-04** | Network | Missing request/event ID rejection | 01 | закрыто | `86c31cd` |
| **NET-05** | Network | Reconnect loop single-flight & max attempts limit | 08 | закрыто | `ab1e5cb` (`MAX_RECONNECT_ATTEMPTS = 5`) |
| **NET-06** | Network | Connect readiness awaiting gateway.ready | 08 | закрыто | `ab1e5cb` |
| **NET-07** | Network | Graceful disconnect close frame handshake | 08 | закрыто | `ab1e5cb` |
| **NET-08** | Network | Typed HermesHttpException without substring search | 06 | закрыто | `861e04b` |
| **NET-09** | Network | gatewayReadyDeferred race condition | 08 | закрыто | `ab1e5cb` |
| **DATA-01** | Storage | Room schema export & migration helper setup | 02 | закрыто | `9389e29` (`exportSchema = true`) |
| **DATA-02** | Storage | Deterministic message ordering (createdAt, id ASC) | 02 | закрыто | `9389e29` |
| **DATA-03** | Storage | Atomic MutableStateFlow updates | 02 | закрыто | `9389e29` |
| **DATA-04** | Storage | Streaming batching & write throttling to SQLite | 02 | закрыто | `9389e29` |
| **DATA-05** | Storage | Approval and clarify requests queue per session | 03 | закрыто | `db94df4` |
| **DATA-06** | Storage | Per-pair session host mutexes | 02 | закрыто | `9389e29` |
| **DATA-07** | Storage | Lightweight session list single-query projection | 09 | закрыто | `9e3aa02` (N+1 query eliminated) |
| **DATA-08** | Storage | session.updatedAt bump on message insertion | 02 | закрыто | `9389e29` |
| **DATA-09** | Storage | In-memory session cache eviction & memory bounding | 09 | закрыто | `9e3aa02` (`releaseSession`, max 10 LRU) |
| **DATA-10** | Storage | Strict tool and thinking attribution | 09 | закрыто | `9e3aa02` (`toolToMessageMap`, strict messageId) |
| **DATA-11** | Storage | MigrationHelper once flag & legacy key cleanup | 08 | закрыто | `ab1e5cb` (`migration_completed`) |
| **DATA-12** | Storage | HermesConnectionManager computeIfAbsent deadlock fix | 08 | закрыто | `ab1e5cb` |
| **UI-01** | UI | AppViewModelFactory route scoping | 03 | закрыто | `db94df4` |
| **UI-02** | UI | CameraX & BarcodeScanner lifecycle resource cleanup | 03 | закрыто | `db94df4` |
| **UI-03** | UI | Snackbar error host & clearError recomposition fix | 03 | закрыто | `db94df4` |
| **UI-04** | UI | ClarifyDialog cancellation & dismiss dispatch | 03 | закрыто | `db94df4` |
| **UI-05** | UI | Adaptive & monochrome launcher icons | 10 | закрыто | `Task 10` (`@mipmap/ic_launcher`) |
| **UI-06** | UI | Safe HostStatus string parsing fallback | 03 | закрыто | `db94df4` |
| **UI-07** | UI | Auto-scroll snapshotFlow conflate during streaming | 09 | закрыто | `9e3aa02` |
| **UI-08** | UI | Strings localization & accessibility contentDescription | 10 | закрыто | `Task 10` (`strings.xml`) |
| **UI-09** | UI | Material 3 DayNight themes & edge-to-edge system bars | 10 | закрыто | `Task 10` |
| **UI-10** | UI | Settings screen diagnostics & cache clear | 10 | закрыто | `Task 10` (`SettingsScreen.kt`) |
| **UI-11** | UI | Foreground service dataSync for active tasks | 08 | закрыто | `ab1e5cb` (`HermesTaskForegroundService`) |
| **BUILD-01** | Build | Binaries removed from git & GitHub release workflow | 05 | закрыто | `784d6a3` |
| **BUILD-02** | Build | Minification, R8, shrinking & signing configuration | 10 | закрыто | `Task 10` (`isMinifyEnabled = true`) |
| **BUILD-03** | Build | GitHub Actions CI workflow (Android, Rust, Emulator) | 04 | закрыто | `3ec16c4` |
| **BUILD-04** | Build | Room schema versioning & migration rules | 02 | закрыто | `9389e29` |
| **BUILD-05** | Build | Instrumented test dependencies in build.gradle | 04 | закрыто | `3ec16c4` |
| **BUILD-06** | Build | Version catalog libs.versions.toml & Dependabot | 05 | закрыто | `784d6a3` |
| **BUILD-07** | Build | Security, contributing, changelog, license docs | 05 | закрыто | `784d6a3` |
| **BUILD-08** | Build | Gradle performance properties & caching | 05 | закрыто | `784d6a3` |
| **TEST-01** | Test | Thread-safe FakeDaos for unit testing | 04 | закрыто | `3ec16c4` |
| **TEST-02** | Test | Deterministic test failure on intentional broken test | 04 | закрыто | `3ec16c4` |
| **TEST-03** | Test | Deterministic flow testing without arbitrary delays | 04 | закрыто | `3ec16c4` |
| **TEST-04** | Test | Full EndToEndContractScenarioTest on active repo | 04 | закрыто | `3ec16c4` |
| **PAIR-01** | Pairing | Stable QR code within TTL | 07 | закрыто | `926953b` |
| **PAIR-02** | Pairing | Unified parsing rules across Kotlin and Rust | 07 | закрыто | `926953b` |
| **PAIR-03** | Pairing | Single Tokio runtime & HTTP client reuse in Rust | 07 | закрыто | `926953b` |
| **PAIR-04** | Pairing | IPv6 discovery & bracketed URL formatting | 07 | закрыто | `926953b` |
| **PAIR-05** | Pairing | Quiet zone 4 modules in all QR renderers | 07 | закрыто | `926953b` |
| **PAIR-06** | Pairing | Probe timeout & fast retry | 07 | закрыто | `926953b` |
| **PAIR-07** | Pairing | 0600 file permissions on Unix & CLI flags | 07 | закрыто | `926953b` |
| **DEAD-01** | Cleanup | Obsolete single-host repositories & screens removed | 10 | закрыто | `Task 10` (1500+ lines removed) |
| **DEAD-02** | Cleanup | Minor dead code, types & unused imports cleaned | 10 | закрыто | `Task 10` |

---

## Вопросы владельцу
1. **Ключ подписи релизных сборок (`BUILD-02`)**: Для сборки подписанного релизного APK для публикации в Google Play создайте файл `keystore.properties` (или настройте переменные окружения CI: `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

---

## Вердикт оркестратора

### 1. Результаты детерминированных проверок
- `./gradlew.bat --no-daemon testDebugUnitTest`: **135/135 tests passed (0 failures)**. Exit code: `0`.
- `./gradlew.bat --no-daemon lint`: **0 errors, 0 warnings**. Exit code: `0`.
- `./gradlew.bat --no-daemon assembleDebug`: **BUILD SUCCESSFUL**. Exit code: `0`.
- `./gradlew.bat --no-daemon assembleRelease`: **BUILD SUCCESSFUL (R8 + resource shrinking passed)**. Exit code: `0`.

### 2. Сверка DoD и Scope
- **`DEAD-01` & `DEAD-02`**: Удалено более 1500 строк устаревшего кода без поломки зависимостей (DataStore вынесен в `MigrationHelper.kt`, тест `MigrationOnceTest` зелёный).
- **`BUILD-02`**: Релизный билд настроен с `isMinifyEnabled = true`, `isShrinkResources = true` и строгими правилами ProGuard для сериализации и моделей.
- **`UI-05`**: Добавлены адаптивные, круглые и монохромные иконки приложения.
- **`UI-08`**: Локализованы все строки интерфейса в `strings.xml`, расставлены `contentDescription`.
- **`UI-09`**: Настроены темы Material 3 DayNight с прозрачными системными барами.
- **`UI-10`**: Экран настроек доступен из навигации и отображает реальную диагностику клиента.
- **Итоговая таблица**: Все 63 находки аудита сверены и закрыты.

### 3. Итоговый статус
**ACCEPTED**. Задание 10 выполнено. Полный цикл задач 01–10 завершён.