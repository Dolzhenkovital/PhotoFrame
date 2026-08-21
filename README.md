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

**Фаза 0 — інфраструктура** ✅: каркас проєкту, скіли для Claude Code
(`.claude/skills/`), CI/CD. Функціональність — у розробці.

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

Для LLM-функцій потрібен секрет **`OPENAI_API_KEY`** (Settings → Secrets and
variables → Actions). Опційно: змінна `OPENAI_MODEL` (за замовчуванням
`gpt-5-mini`). Без секрета CI працює, LLM-кроки тихо пропускаються.

## Ліцензія

[MIT](LICENSE)
