# Task 05: Гигиена репозитория — бинарники, зависимости, документы

**Date**: 2026-08-24  
**Base SHA**: `3ec16c492793a45117c3451b1b257086d752827a`  
**Task Spec**: `agy-work/TASK-2026-08-24-05-repo-hygiene.md`  

---

## ## Кодер 1

### 1. Выполненные действия по §Scope

#### Scope 1 (BUILD-01 — Удаление бинарников из Git, Release Job, Документация)
1. **Удаление бинарников из индекса и дерева**:
   - Выполнена команда `git rm -r --cached hermes-pair/dist`.
   - Удалены файлы `hermes-pair/dist/linux/hermes-pair` (15.9 MB) и `hermes-pair/dist/windows/HermesPair.exe` (21.2 MB).
   - В [`.gitignore`](file:///e:/Agent%20projects/hermes-android-apk/.gitignore) добавлены правила: `hermes-pair/dist/`, `dist/`, `**/dist/`.
   - Проверено: `git ls-files hermes-pair/dist` возвращает пустой вывод.
2. **Release Workflow в CI**:
   - В [`.github/workflows/ci.yml`](file:///e:/Agent%20projects/hermes-android-apk/.github/workflows/ci.yml) добавлены триггеры на теги `v*` (`tags: ['v*']`).
   - Добавлены джобы:
     - `build-pair-linux` (Ubuntu, `cargo build --release`, сборка `hermes-pair-linux-x86_64`).
     - `build-pair-windows` (Windows, `cargo build --release`, сборка `hermes-pair-windows-x86_64.exe`).
     - `publish-release` (Ubuntu, выгрузка собранных бинарников, вычисление `SHA256SUMS.txt` из тех же файлов в джобе, публикация релиза с бинарниками и контрольными суммами через `softprops/action-gh-release@v2`).
3. **Обновление README**:
   - В [`README.md`](file:///e:/Agent%20projects/hermes-android-apk/README.md) удалены все ссылки на `./hermes-pair/dist/...`.
   - Добавлены секции для загрузки бинарников из GitHub Releases и сборки из исходников (`cargo build --release`).
   - Добавлены команды проверки контрольных сумм SHA-256 для Windows (`Get-FileHash ... -Algorithm SHA256`) и Linux (`sha256sum -c SHA256SUMS.txt`).

#### Scope 2 (BUILD-06 — Version Catalog & Dependabot)
1. **Gradle Version Catalog**:
   - Создан [`gradle/libs.versions.toml`](file:///e:/Agent%20projects/hermes-android-apk/gradle/libs.versions.toml).
   - Все версии плагинов и библиотек из [`build.gradle.kts`](file:///e:/Agent%20projects/hermes-android-apk/build.gradle.kts) и [`app/build.gradle.kts`](file:///e:/Agent%20projects/hermes-android-apk/app/build.gradle.kts) перенесены без изменения ни одной версии (диф версий пустой).
   - В `build.gradle.kts` и `app/build.gradle.kts` плагины и зависимости переведены на `alias(libs.plugins...)` и `libs...`.
2. **Dependabot**:
   - Создан [`.github/dependabot.yml`](file:///e:/Agent%20projects/hermes-android-apk/.github/dependabot.yml) с еженедельным расписанием для экосистем `gradle` (директория `/`) и `cargo` (директория `/hermes-pair`).

#### Scope 3 (BUILD-07 — Документы репозитория)
1. [`SECURITY.md`](file:///e:/Agent%20projects/hermes-android-apk/SECURITY.md): Политика сообщения об уязвимостях (приватные отчеты, срок первичного ответа 48 часов, оценка 7 дней, описание механизмов безопасности).
2. [`CONTRIBUTING.md`](file:///e:/Agent%20projects/hermes-android-apk/CONTRIBUTING.md): Руководство по сборке Android и Rust частей, запуск верификационных тестов и линтеров, требования к Pull Requests.
3. [`CHANGELOG.md`](file:///e:/Agent%20projects/hermes-android-apk/CHANGELOG.md): Формат Keep a Changelog, зафиксированы изменения задач 01–04 и задачи 05.
4. [`LICENSE`](file:///e:/Agent%20projects/hermes-android-apk/LICENSE): Добавлен файл лицензии (MIT License по умолчанию из `hermes-pair/README.md`) с пометкой о подтверждении владельцем.

#### Scope 4 (BUILD-08 — Оптимизация Gradle)
1. В [`gradle.properties`](file:///e:/Agent%20projects/hermes-android-apk/gradle.properties) добавлены:
   - `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`
   - `org.gradle.parallel=true`
   - `org.gradle.caching=true`
   - `android.nonFinalResIds=true`
   - `org.gradle.configuration-cache=true`
2. Проведено тестирование флага `org.gradle.configuration-cache=true`:
   - `./gradlew --no-daemon testDebugUnitTest` — успешно пройден (22s при повторном запуске с использованием кеша).
   - `./gradlew --no-daemon lint` — успешно пройден (`Configuration cache entry stored`).
   - `./gradlew --no-daemon assembleDebug` — успешно пройден (`Configuration cache entry stored`, APK собран).

---

### 2. Список изменённых и созданных файлов

| Файл | Статус | Назначение |
|---|---|---|
| `hermes-pair/dist/linux/hermes-pair` | Удалён | Удаление бинарника из git tracking |
| `hermes-pair/dist/windows/HermesPair.exe` | Удалён | Удаление бинарника из git tracking |
| `.gitignore` | Изменён | Игнорирование `hermes-pair/dist/`, `dist/`, `**/dist/` |
| `.github/workflows/ci.yml` | Изменён | Добавление release jobs для Windows и Linux + `SHA256SUMS.txt` |
| `README.md` | Изменён | Удаление путей к `dist/`, добавление инструкций по Releases, сборке и SHA-256 |
| `gradle/libs.versions.toml` | Создан | Централизованный каталог версий Gradle |
| `build.gradle.kts` | Изменён | Использование `libs.plugins` |
| `app/build.gradle.kts` | Изменён | Использование `libs.plugins` и `libs.*` |
| `.github/dependabot.yml` | Создан | Еженедельные проверки обновлений Gradle и Cargo |
| `SECURITY.md` | Создан | Политика безопасности |
| `CONTRIBUTING.md` | Создан | Руководство для контрибьюторов |
| `CHANGELOG.md` | Создан | Журнал изменений (Keep a Changelog) |
| `LICENSE` | Создан | Файл лицензии |
| `gradle.properties` | Изменён | Оптимизации Gradle: параллелизм, кеш, 4G heap, nonFinalResIds, configuration-cache |

---

### 3. Результаты проверок

#### 3.1. Проверка отсутствия бинарников в git
```powershell
PS E:\Agent projects\hermes-android-apk> git ls-files hermes-pair/dist
# Вывод пуст
```

#### 3.2. Unit Tests
```text
.\gradlew.bat --no-daemon testDebugUnitTest
BUILD SUCCESSFUL in 22s
25 actionable tasks: 25 up-to-date
Configuration cache entry stored.
```

#### 3.3. Android Lint
```text
.\gradlew.bat --no-daemon lint
BUILD SUCCESSFUL in 1m 45s
28 actionable tasks: 9 executed, 1 from cache, 18 up-to-date
Configuration cache entry stored.
```

#### 3.4. Assemble Debug APK
```text
.\gradlew.bat --no-daemon assembleDebug
BUILD SUCCESSFUL in 2m 34s
37 actionable tasks: 19 executed, 18 up-to-date
Configuration cache entry stored.
```

#### 3.5. Git Diff Stat
```text
 .github/dependabot.yml   |  13 +++++
 .github/workflows/ci.yml | 111 +++++++++++++++++++++++++++++++++++++++++++++++
 .gitignore               |   4 ++
 CHANGELOG.md             |  33 ++++++++++++++
 CONTRIBUTING.md          |  56 ++++++++++++++++++++++++
 LICENSE                  |  21 +++++++++
 README.md                |  46 ++++++++++++++++----
 SECURITY.md              |  34 +++++++++++++++
 app/build.gradle.kts     |  96 ++++++++++++++++++++--------------------
 build.gradle.kts         |  10 ++---
 gradle.properties        |   9 +++-
 gradle/libs.versions.toml|  68 +++++++++++++++++++++++++++++
 12 files changed, 437 insertions(+), 64 deletions(-)
```

---

## ## Вопросы владельцу

1. **История git и бинарники (`BUILD-01`)**:
   - **Вариант A (выполнен сейчас)**: Бинарники удалены текущим коммитом. Они исключены из дальнейшего версионирования через `.gitignore`. Локальные клоны разработчиков не ломаются, но прошлая история в git сохраняет ~37 МБ.
   - **Вариант B (требует решения владельца)**: Переписать историю через `git filter-repo` / BFG Repo-Cleaner и сделать force push. Размер репозитория уменьшится до ~1 МБ, однако все существующие форки и локальные ветки потребуют перебазирования (`git pull --rebase` или пересоздание клона).
   - *Вопрос: Оставляем вариант A или выполняем вариант B с force push?*

2. **Выбор лицензии (`BUILD-07`)**:
   - Создан шаблон `LICENSE` на базе MIT License (так как в `hermes-pair/README.md` была ссылка на MIT).
   - *Вопрос: Подтверждает ли владелец лицензию MIT, либо требуется Apache 2.0 / GPLv3 / проприетарная лицензия?*

3. **Подпись Windows-бинарника `HermesPair.exe` (`BUILD-01`)**:
   - Релизный workflow собирает бинарники и контрольные суммы SHA-256. Windows SmartScreen может предупреждать о неподписанном `.exe` без EV/OV Authenticode сертификата.
   - *Вопрос: Планируется ли приобретение и добавление сертификата подписи кода (Code Signing Certificate) в GitHub Secrets для автоматической подписи Windows-бинарников в CI?*
