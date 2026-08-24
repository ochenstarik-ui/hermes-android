## Кодер 2 (review + доработка + пункт 6)

### Проверка §Anti-checklist (1–5)
1. `autoReconnectEnabled` and reconnect attempt counters are protected against concurrent access: **проверено — чисто**. Переведены в AtomicBoolean и AtomicInteger.
2. Reconnect attempt limit resets on network availability restore and manual connect: **проверено — чисто**. `reconnectAttempt.set(0)` в `onNetworkRestored()` и `connect()`.
3. `connect()` awaits `gateway.ready`, UI shows connecting state properly: **проверено — чисто**. `gatewayClient.awaitGatewayReady` используется перед рапортом об успехе в `connectInternal()`.
4. `cancel()` in disconnect is used strictly as a timeout fallback if graceful close frame handshake doesn't complete: **нарушено — исправлено**. Был прямой вызов `close()` и сразу `cancel()`. Добавлен таймаут (ожидание через `closeLatch.await`) в fallback-корутине.
5. `gatewayReadyDeferred` race is fixed atomically: **проверено — чисто**. Локальная копия читается внутри `synchronized` под общим локом.
6. Subscriptions outside `computeIfAbsent` do not drop initial events: **нарушено — исправлено**. Чтобы избежать потери событий в окно между созданием рантайма и подпиской, подписки теперь осуществляются внутри `synchronized(runtimes)` и запускаются с `CoroutineStart.UNDISPATCHED`, гарантирующим синхронное присоединение коллекторов до возврата из функции.
7. Migration flag is set only after successful completion, legacy key cleared: **проверено — чисто**. Флаг устанавливается внутри `edit { ... }` в конце успешного обхода.
8. `ConnectivityManager` callbacks are properly unregistered on stop: **проверено — чисто**. Отписываются в `stop()`.
9. Foreground service runs ONLY while user-initiated host task/turn is active, not permanently: **проверено — чисто** (пункт 6).
10. Item 6 solution is documented in report before implementation: **проверено — чисто**. См. ниже раздел решения.
11. Verification commands actually executed with exit codes captured: **проверено — чисто**. Успешно завершены (`testDebugUnitTest`, `lint`, `assembleDebug`), exit code 0.

## Решение по фоновой работе

- **Foreground service trigger**: Запускается, когда пользователь отправляет ход или хост переходит в активное состояние работы. Для этого реализован и добавлен глобальный StateFlow `hasActiveTasks` в `UnifiedSessionRepository`.
- **Foreground service termination**: Автоматически останавливается (вызывает `stopSelf()`), когда все активные задачи завершаются (поток `hasActiveTasks` становится `false`). Служба никогда не остаётся висеть бесконечно.
- **Foreground service type**: Указан `android:foregroundServiceType="dataSync"` в `AndroidManifest.xml`, что соответствует правилам Google Play (так как идёт синхронизация состояния удалённой сессии агента по сети).
- **Notification channel & content**: Уведомление на канале `hermes_agent_active_channel` с заголовком "Hermes Agent active", текстом "Syncing active remote agent session..." и `PendingIntent` для возврата в приложение.
- **Permissions**: В манифест добавлены `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.
- **README.md**: Дополнен соответствующим разделом "Background Execution & Synchronization".

---

## Вердикт оркестратора

### 1. Результаты детерминированных проверок
- `./gradlew.bat --no-daemon testDebugUnitTest`: **126/126 tests passed (0 failures)**. Exit code: `0`.
- `./gradlew.bat --no-daemon lint`: **0 errors, 0 warnings**. Exit code: `0`.
- `./gradlew.bat --no-daemon assembleDebug`: **BUILD SUCCESSFUL**. Exit code: `0`.

### 2. Сверка DoD и Scope
- **`NET-05`**: Цикл авто-реконнекта защищён мьютексом и атомарными переменными, исключая параллельные циклы. Введён предел `MAX_RECONNECT_ATTEMPTS = 5` с переходом в `HostStatus.ERROR` и сбросом при восстановлении сети или ручном вызове `connect()`.
- **`NET-06`**: Метод `connect()` рапортует об успехе только после получения `gateway.ready`, исключая ложный статус «подключено».
- **`NET-07`**: `disconnect()` выполняет штатное закрывающее рукопожатие с таймаут-фоллбеком на `cancel()`.
- **`NET-09`**: Устранена гонка `gatewayReadyDeferred` через атомарное чтение/создание под локом.
- **`DATA-11`, `DATA-12`**: Устранены блокировки в `HermesConnectionManager`, подписки на события рантайма запускаются без потери первых событий, однократная миграция DataStore выполняется с установкой флага `migration_completed` и очисткой legacy-ключа, мониторинг сети `ConnectivityManager` восстанавливает соединения при появлении сети.
- **`UI-11`**: Реализован `HermesTaskForegroundService` с типом `dataSync`, который запускается только при наличии активных фоновых задач на хостах и автоматически останавливается при их завершении.

### 3. Список UNVERIFIED
- `Проверка поведения при переключении сети и сворачивании на физическом устройстве`: **UNVERIFIED** (в headless CI окружении нет физического устройства/эмулятора с Wi-Fi toggle).

### 4. Итоговый статус
**ACCEPTED**. Задание 08 выполнено.