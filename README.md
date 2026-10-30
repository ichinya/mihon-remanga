# mihon-remanga

Расширение-источник [Remanga](https://remanga.org) для Mihon и Tachiyomi-форков.
Сборка и публикация полностью автоматические (GitHub Actions).

## Установка

**Вариант 1 — через репозиторий расширений** (рекомендуется, обновления приходят сами):

В приложении: **Ещё → Настройки → Расширения → Репозитории расширений → Добавить**

```
https://raw.githubusercontent.com/ichinya/mihon-remanga/repo/index.min.json
```

Запасная ссылка (GitHub Pages), если raw-адрес не открывается:

```
https://ichinya.github.io/mihon-remanga/index.min.json
```

**Вариант 2 — вручную:** скачать APK и открыть его на телефоне.

- Страница релизов: <https://github.com/ichinya/mihon-remanga/releases/latest>
- Стабильная ссылка на последний APK (имя файла не меняется):
  <https://github.com/ichinya/mihon-remanga/releases/latest/download/Remanga.apk>
- APK с версией в имени (для истории):
  `https://github.com/ichinya/mihon-remanga/releases/latest/download/tachiyomi-ru.remanga-v1.6.N.apk`
  (точное имя — в описании последнего релиза)

**Важно:** APK подписан debug-ключом. При переходе со старых сборок Remanga
(tachiyomiorg/NSeydulla/e-shl) удалите старую версию перед установкой — библиотека
не пострадает: id источника сохранён (`8983242087533137528`), данные подхватятся.

## Версионирование

Версия считается автоматически: `versionCode = 117 + номер сборки` (номер растёт
с каждым push в `main`), versionName — `1.6.N`. Каждая сборка:

1. собирает release-APK (`:src:ru:remanga:assembleRelease`),
2. публикует релиз `v1.6.N` с двумя файлами (версионированный APK и `Remanga.apk`),
3. обновляет ветку [`repo`](https://github.com/ichinya/mihon-remanga/tree/repo) —
   `index.min.json`, `apk/`, `icon/` — для репозитория расширений.

## Структура

- `src/ru/remanga/` — сам источник (KeiSource, libVersion 1.6):
  каталог, поиск с фильтрами (сортировка/тип/возраст), детали, главы всех веток
  переводчиков, страницы; платные главы помечаются 🔒; зеркало для картинок CDN.
- **Диагностика ошибок**: сетевые сбои (TLS, DNS, HTTP ≥400, HTML-страница
  DDoS-Guard вместо API) сохраняются в лог на устройстве
  (`Android/data/<приложение>/files/remanga-diagnostics.log`), при ошибке
  показывается тост «ошибка сохранена в лог». В настройках источника — раздел
  «Диагностика»: последняя ошибка, шеринг лога, очистка.
- `core/`, `compiler/`, `gradle/`, `common/` — сборочная система keiyoushi
  (Apache-2.0), база — форк [avustr-code/extensions-source](https://github.com/avustr-code/extensions-source)
  от [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).

## Сборка локально

Нужны JDK 17+ и Android SDK.

```bash
./gradlew :src:ru:remanga:assembleRelease
# → src/ru/remanga/build/outputs/apk/release/tachiyomi-ru.remanga-v1.6.117.apk
```

## Известные ограничения

- API remanga.org защищён DDoS-Guard с гео-блокировкой: из некоторых стран/сетей
  сайт недоступен по IP (ошибка 403) — лечится только сменой IP/VPN.
- Ошибка `Trust anchor for certification path not found` в приложении при
  работающем в браузере сайте — перехват TLS или подмена DNS на устройстве:
  включите DNS over HTTPS в настройках Mihon (Настройки → Дополнительно) и/или
  системный Приватный DNS (`dns.google`).
