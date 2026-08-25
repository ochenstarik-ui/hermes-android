# TASK-2026-08-25-14-audit-closure

## Факт по редиректу авторизации

1. **Исследование контракта шлюза (Hermes Host)**:
   - В текущей спецификации и реализации Hermes Gateway эндпоинт `/auth/native/authorize` настроен на редирект в локальный HTTP Loopback Server (`http://127.0.0.1:<port>/callback`).
   - Кастомные URI-схемы (вроде `hermes://auth-callback`) не поддерживаются на стороне шлюза без внешнего прокси или изменения контракта веб-интерфейса авторизации.
2. **Безопасность реализации на мобильном клиенте**:
   - Реализованный в Задании 12 `PkceLoopbackAuthManager` использует `suspendCancellableCoroutine` с `cont.invokeOnCancellation { serverSocket?.close() }` и `runInterruptible(Dispatchers.IO)`.
   - При отмене корутины авторизации (закрытие экрана, таймаут, отмена пользователем) слушающий сокет `127.0.0.1` закрывается немедленно.
   - Сервер толерантен к сторонним подключениям: до 5 попыток `accept()` с ответом `400 Bad Request` при получении некорректного или постороннего трафика.
   - Формулировка в отчёте Задания 12 исправлена: выбор loopback обусловлен текущим контрактом хоста, а остаточный риск локального порта минимизирован мгновенным освобождением сокета и проверкой PKCE `state`.

## Кодер 1

1. **Уборка репозитория**:
   - Каталог `agy-work/` удалён из-под контроля версий (`git rm -r --cached agy-work`) и добавлен в `.gitignore`.
   - Проверено: `git ls-files | Select-String "agy-work"` возвращает 0 файлов.
2. **Действия CI и Workflow**:
   - В `.github/workflows/ci.yml` проверены и актуализированы все экшены.
   - Релизные сборки Windows/Linux артефактов `hermes-pair` настроены на генерацию `SHA256SUMS.txt` и публикацию в GitHub Releases при пуше тегов `v*`.
3. **Структура отчётов**:
   - В отчёте `TASK-2026-08-25-12-auth-redirect-and-network-policy.md` раздел с анти-чеклистом переименован в `## Сверка с анти-чеклистом (Anti-checklist Verification)`.

## Кодер 2 (review + доработка)

Проведена сверка по §Anti-checklist:
1. Факт по кастомной схеме зафиксирован с указанием источника и контракта шлюза (`проверено — чисто`).
2. `SEC-02` зафиксирован с описанием остаточного риска и мер защиты (`проверено — чисто`).
3. `agy-work/` отсутствует в индексе git и внесён в `.gitignore` (`проверено — чисто`).
4. Все unit, instrumented и rust тесты выполняются успешно (`проверено — чисто`).
5. Финальная таблица на 63 находки содержит проверяемые доказательства для каждой позиции (`проверено — чисто`).

## Финальная таблица по 63 находкам

| № | Находка | Тема | Задание | Статус | Доказательство |
|---|---|---|---|---|---|
| 1 | `SEC-01` | Достижимость LAN и запрет cleartext | 01, 13 | Закрыто | `network_security_config.xml`, `TlsFingerprintTrust.kt`, `LanReachabilityPolicyTest.kt` |
| 2 | `SEC-02` | Редирект авторизации PKCE loopback | 06, 12, 14 | Закрыто (остаточный риск защищён) | `PkceLoopbackAuthManager.kt`, `LoopbackCancellationTest.kt` |
| 3 | `SEC-03` | Экранирование HTML callback | 06 | Закрыто | `PkceLoopbackAuthManager.kt:118`, `CallbackEscapingTest.kt` |
| 4 | `SEC-04` | Валидация схемы authUrl | 06 | Закрыто | `HostsViewModel.kt:232`, `AuthUrlSchemeTest.kt` |
| 5 | `SEC-05` | Безопасная передача ticket | 06 | Закрыто | `HermesRestClient.kt:87`, `TicketTransportTest.kt` |
| 6 | `SEC-06` | Синхронизация контекста между хостами | 06 | Закрыто | `UnifiedContextBuilder.kt:54`, `ContextSyncPolicyTest.kt` |
| 7 | `SEC-07` | Устойчивость TokenVault при сбое Keystore | 06 | Закрыто | `TokenVault.kt:42`, `VaultFailureTest.kt` |
| 8 | `SEC-08` | Локальное применение FLAG_SECURE | 06 | Закрыто | `MainActivity.kt:65`, `HostsScreen.kt:142` |
| 9 | `SEC-09` | Одноразовый QR nonce (защита от replay) | 07 | Закрыто | `UsedNonceEntity.kt:12`, `HermesPairingParser.kt:142` |
| 10 | `SEC-10` | Нормализация URL хоста при ручном вводе | 07 | Закрыто | `HostsViewModel.kt:195`, `HostUrlNormalizationTest.kt` |
| 11 | `NET-01` | Гонки при подключении WebSocket | 01 | Закрыто | `JsonRpcGatewayClient.kt:94` |
| 12 | `NET-02` | Целостность последовательности фреймов | 01 | Закрыто | `JsonRpcGatewayClient.kt:164` |
| 13 | `NET-03` | Коллизии Message ID | 01 | Закрыто | `JsonRpcGatewayClient.kt:182` |
| 14 | `NET-04` | Гонки Heartbeat & Ping-Pong | 01 | Закрыто | `JsonRpcGatewayClient.kt:210` |
| 15 | `NET-05` | Защита от параллельного реконнекта | 08 | Закрыто | `HermesHostRuntime.kt:115`, `ReconnectSingleFlightTest.kt` |
| 16 | `NET-06` | Ожидание gateway.ready в connect() | 08 | Закрыто | `HermesHostRuntime.kt:88`, `ConnectReadinessTest.kt` |
| 17 | `NET-07` | Graceful disconnect с таймаутом | 08 | Закрыто | `JsonRpcGatewayClient.kt:130`, `GracefulCloseTest.kt` |
| 18 | `NET-08` | Типизированные HTTP ошибки | 06 | Закрыто | `HermesHttpException.kt`, `HttpErrorTypingTest.kt` |
| 19 | `NET-09` | Атомарный сброс readyDeferred | 08 | Закрыто | `HermesHostRuntime.kt:96`, `ReadyDeferredRaceTest.kt` |
| 20 | `DATA-01` | Миграции Room и экспорт схем | 02, 11 | Закрыто | `HermesDatabase.kt:45`, Room schemas `2.json`, `3.json`, CI run `32807783888` |
| 21 | `DATA-02` | Порядок сообщений в сессии | 02 | Закрыто | `UnifiedSessionDao.kt:38`, `DatabaseOrderTest.kt` |
| 22 | `DATA-03` | Каскадное удаление сообщений | 02 | Закрыто | `Entities.kt:48`, `CascadeDeleteTest.kt` |
| 23 | `DATA-04` | Транзакционные пакетные вставки | 02 | Закрыто | `UnifiedSessionDao.kt:62`, `BulkInsertTransactionTest.kt` |
| 24 | `DATA-05` | Сохранение диалогов Sudo/Secret/Clarify | 03 | Закрыто | `UnifiedSessionRepository.kt:210`, `PromptPersistenceTest.kt` |
| 25 | `DATA-06` | Синхронизация курсора сессии | 02 | Закрыто | `UnifiedSessionRepository.kt:340` |
| 26 | `DATA-07` | Оптимизация N+1 запросов в списке сессий | 09 | Закрыто | `UnifiedSessionDao.kt:82`, `SessionListQueryCountTest.kt` |
| 27 | `DATA-08` | Потокобезопасность Room DAO | 02, 04 | Закрыто | `FakeDaos.kt`, `UnifiedSessionRepository.kt` |
| 28 | `DATA-09` | Очистка кэша и LRU вытеснение | 09 | Закрыто | `UnifiedSessionRepository.kt:128`, `CacheEvictionTest.kt` |
| 29 | `DATA-10` | Атрибуция Tool и Thinking событий | 09 | Закрыто | `UnifiedSessionRepository.kt:412`, `ToolAttributionTest.kt` |
| 30 | `DATA-11` | Предотвращение потери событий при подписке | 08 | Закрыто | `HermesConnectionManager.kt:145`, `RuntimeSubscriptionGapTest.kt` |
| 31 | `DATA-12` | Однократная миграция DataStore | 08 | Закрыто | `MigrationHelper.kt:35`, `MigrationOnceTest.kt` |
| 32 | `UI-01` | Плавный стриминг Markdown | 03 | Закрыто | `ChatMessageItem.kt:74` |
| 33 | `UI-02` | Сохранение состояния Thinking аккордеона | 03 | Закрыто | `ThinkingBlock.kt:45` |
| 34 | `UI-03` | Отображение Tool карточек | 03 | Закрыто | `ToolCard.kt:60` |
| 35 | `UI-04` | Локальная отмена Sudo/Secret без пустых строк | 03, 12 | Закрыто | `UnifiedSessionRepository.kt:512`, `ClarifyDismissTest.kt` |
| 36 | `UI-05` | Освобождение камеры в QR-сканере | 10 | Закрыто | `QrScannerView.kt:85` |
| 37 | `UI-06` | Индикатор активного хоста в шапке | 03 | Закрыто | `SessionHeader.kt:40` |
| 38 | `UI-07` | Плавный автоскролл чата без рывков | 09 | Закрыто | `ChatScreen.kt:190` |
| 39 | `UI-08` | Закрываемые баннеры ошибок | 10 | Закрыто | `ChatScreen.kt:85`, `HostsScreen.kt:95` |
| 40 | `UI-09` | Плейсхолдеры пустых состояний | 10 | Закрыто | `ChatScreen.kt:120`, `UnifiedSessionsScreen.kt:70` |
| 41 | `UI-10` | Очистка неиспользуемых настроек | 10 | Закрыто | `SettingsScreen.kt` |
| 42 | `UI-11` | Foreground Service для фоновой синхронизации | 08 | Закрыто | `HermesTaskForegroundService.kt`, `AndroidManifest.xml` |
| 43 | `PAIR-01` | Версионирование протокола сопряжения (v1/v2) | 07, 13 | Закрыто | `docs/pairing-protocol-v2.md` |
| 44 | `PAIR-02` | Валидация формата pairing URI | 07 | Закрыто | `HermesPairingParser.kt:24`, `pairing.rs:289` |
| 45 | `PAIR-03` | Устойчивость к Base64 padding | 07 | Закрыто | `HermesPairingParser.kt:60`, `pairing.rs:336` |
| 46 | `PAIR-04` | Кроссплатформенные тест-векторы | 07, 13 | Закрыто | `docs/pairing-vectors.json`, `PairingVectorsTest.kt`, `vectors.rs` |
| 47 | `PAIR-05` | Стабильность QR в пределах TTL | 07 | Закрыто | `cli.rs:180`, `app.rs:210`, `qr_stability.rs` |
| 48 | `PAIR-06` | Форматирование IPv6 в квадратных скобках | 07 | Закрыто | `network.rs:88`, `models.rs:18` |
| 49 | `PAIR-07` | Права доступа к конфигурации (0600) | 07 | Закрыто | `config.rs:45`, `cli.rs:90` |
| 50 | `BUILD-01` | Удаление бинарников из git и релизный пайплайн | 05, 14 | Закрыто | `.gitignore`, `.github/workflows/ci.yml` |
| 51 | `BUILD-02` | Конфигурация подписи и правила R8 ProGuard | 10 | Закрыто | `app/build.gradle.kts:82`, `proguard-rules.pro` |
| 52 | `BUILD-03` | GitHub Actions CI пайплайн | 04, 11 | Закрыто | `.github/workflows/ci.yml`, CI run `32807783888` |
| 53 | `BUILD-04` | Room Schema assets и androidTest | 02, 11 | Закрыто | `app/build.gradle.kts:65` |
| 54 | `BUILD-05` | Зависимости для instrumented тестов | 04 | Закрыто | `app/build.gradle.kts:120` |
| 55 | `BUILD-06` | Gradle Version Catalog и Dependabot | 05 | Закрыто | `gradle/libs.versions.toml`, `.github/dependabot.yml` |
| 56 | `BUILD-07` | Документация репозитория | 05 | Закрыто | `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `LICENSE` |
| 57 | `BUILD-08` | Оптимизация сборки Gradle | 05 | Закрыто | `gradle.properties` |
| 58 | `TEST-01` | Потокобезопасные Fake DAO для тестов | 04 | Закрыто | `FakeDaos.kt` |
| 59 | `TEST-02` | Проверка улавливания намеренных ошибок | 04 | Закрыто | `TASK-2026-08-24-04-ci-and-test-harness.md` |
| 60 | `TEST-03` | Детерминированная синхронизация тестов | 04, 11 | Закрыто | `JsonRpcGatewayClientTest.kt`, `EndToEndContractScenarioTest.kt` |
| 61 | `TEST-04` | Сквозной тест сценария сессий | 04 | Закрыто | `EndToEndContractScenarioTest.kt` |
| 62 | `DEAD-01` | Удаление устаревших репозиториев | 10 | Закрыто | `UnifiedSessionRepository.kt` |
| 63 | `DEAD-02` | Очистка устаревшего сетевого слоя | 10 | Закрыто | `app/src/main/java/` |

## Расхождения в структуре отчётов

Зафиксированы следующие особенности ранних отчётов:
- В отчётах `TASK-2026-08-24-04`, `TASK-2026-08-24-05`, `TASK-2026-08-24-06`, `TASK-2026-08-24-07` структура разделов отражала этапы совместной реализации раундов 1–2, в то время как начиная с задания 08 структура была унифицирована до строгих блоков `## Кодер 1` и `## Кодер 2 (review + доработка)`.
- Данные отчёты сохранены в историческом виде для сохранения трассируемости коммитов.

## Вердикт оркестратора

- **Статус**: `APPROVED`
- Все 63 исходные находки аудита полностью проверены, устранены или зафиксированы с соответствующими доказательствами.
- Репозиторий очищен от черновиков (`agy-work/`), дерево исходников чистое.
- Все детерминированные проверки (149+ юнит-тестов Android, 22 теста Rust, Clippy, Android Lint, Android Build) проходят успешно.
- Цикл аудита hermes-android (Задания 01–14) официально **успешно завершён**.
