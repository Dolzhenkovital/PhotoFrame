# PhotoFrame

[![Android CI](https://github.com/Dolzhenkovital/PhotoFrame/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Dolzhenkovital/PhotoFrame/actions/workflows/android-ci.yml)

Застосунок цифрової фоторамки для Android **6.0+**, оптимізований під старе
слабке залізо (від ~1 ГБ RAM).

## Можливості (план)

- 📷 **Google Photos** через Photos Picker API — обрані фото завантажуються в
  локальний кеш (розмір налаштовується, за замовчуванням **1 ГБ**) і
  показуються офлайн
- 📁 **Локальні фото** — тека на пристрої, SD-картці чи USB (через SAF, без
  зайвих дозволів)
- 🔄 **Орієнтація**: вертикальна рамка показує переважно вертикальні фото,
  горизонтальна — горизонтальні
- ⏱️ Таймер зміни фото з пресетами (5 с … 1 год)
- ✨ ~10 ефектів переходу (crossfade, слайди, zoom, Ken Burns…) + випадковий
- 🎞️ Motion Photos (вимикається в налаштуваннях)

## Статус

- **Фаза 0 — інфраструктура** ✅: каркас проєкту, скіли для Claude Code
  (`.claude/skills/`), CI/CD
- **Фаза 1 — ядро слайдшоу** ✅: локальні фото (SAF-тека, без дозволів),
  черга з підбором за орієнтацією, таймер із пресетами, 10 ефектів переходу
  + випадковий, екран налаштувань (EN/UK)
- **Фаза 2 — Google Photos** ✅: OAuth без секретів у коді, вибір фото через
  Picker API (QR-код для телефона або локально), дисковий кеш із лімітом
  (за замовчуванням 1 ГБ) та LRU-евікшеном, офлайн-показ
- **Фаза 3 — Motion Photos + реліз** ✅: «живі» фото (формати Google
  MicroVideo/MotionPhoto v1, без розпакування), перемикач «заповнювати
  екран», релізний workflow: тег `vX.Y.Z` → підписаний APK у GitHub Releases

## Налаштування Google Photos (одноразово)

Вхід працює через системний браузер (loopback + PKCE, RFC 8252) — без
Google Play services, тому він доступний і на старих рамках без GMS.
Потрібен OAuth-клієнт типу **Desktop app**:

1. [Google Cloud Console](https://console.cloud.google.com) → створіть проєкт
   → увімкніть **Photos Picker API**.
2. **OAuth consent screen**: тип External, додайте scope
   `photospicker.mediaitems.readonly`, себе — у Test users.
3. **Credentials → Create OAuth client ID → Desktop app** → скопіюйте
   Client ID і Client secret.
4. Передайте їх у збірку (у git вони не потрапляють):
   - локально — у `local.properties`:
     `gp.oauthClientId=…` та `gp.oauthClientSecret=…`;
   - у CI — env-змінні/секрети `GP_OAUTH_CLIENT_ID` і
     `GP_OAUTH_CLIENT_SECRET`.

Для installed-app клієнтів client secret не вважається таємницею
(RFC 8252 §8.5) — з репозиторію його тримаємо подалі лише щоб чужі форки
не палили квоту цього проєкту. SHA-1 підпису APK більше ні на що не
впливає: будь-яка збірка (Android Studio, CI-артефакт, release) входить у
Google однаково.

Поки consent screen у режимі **Testing**, входити можуть лише акаунти зі
списку Test users, а refresh-токени живуть 7 днів — після цього рамка
попросить увійти знову. Для «увійшов раз — працює роками» переведіть
consent screen у **In production**.

## Збірка

- **Android Studio** (рекомендовано): просто відкрити проєкт — студія має
  вбудований JDK 17.
- **CLI**: потрібні JDK 17 і Gradle 8.9:
  `gradle testDebugUnitTest lintDebug assembleDebug`

## CI/CD

| Workflow | Тригер | Що робить |
|----------|--------|-----------|
| Android CI | push у `main`, PR | юніт-тести → Android Lint → збірка APK (артефакт) |
| LLM PR Review | кожен PR | рев'ю дифу через OpenAI API, один коментар що оновлюється |
| CI Failure Analysis | падіння Android CI | LLM читає логи і коментує діагноз та як полагодити |
| Release | тег `v*` | тести → підписаний release-APK → GitHub Release |
| LLM Smoke Test | вручну (Actions) | діагностика LLM-ендпоінта |

Для релізу обов'язкові секрети `RELEASE_KEYSTORE_BASE64` (base64 файлу
.jks), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` — без них Release-workflow навмисно падає:
непідписаний APK не встановлюється, публікувати його немає сенсу.

Для LLM-функцій потрібен секрет **`OPENAI_API_KEY`** (Settings → Secrets and
variables → Actions). Ендпоінт і модель за замовчуванням:
`https://3xanny-secureapi.hf.space/v1`, `gpt-5.6-sol-medium`
(перевизначаються змінними `OPENAI_BASE_URL` / `OPENAI_MODEL`). Без секрета
CI працює, LLM-кроки тихо пропускаються.

## Ліцензія

[MIT](LICENSE)
