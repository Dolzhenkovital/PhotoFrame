---
name: ci-cd
description: >-
  How PhotoFrame's CI/CD works and how to develop against it: the GitHub
  Actions pipelines (build + unit tests + lint, OpenAI-powered PR review, LLM
  analysis of failed CI runs), required secrets, local build commands, and
  branch/PR conventions. Use this skill whenever the task involves workflows,
  GitHub Actions, tests in CI, the LLM reviewer, release/deploy steps,
  Gradle build problems in CI, or preparing/merging a PR in this repo.
---

# CI/CD for PhotoFrame

## The three pipelines (`.github/workflows/`)

1. **`android-ci.yml`** — every push to `main` and every PR:
   `testDebugUnitTest` → `lintDebug` → `assembleDebug`; uploads the debug APK
   and build reports as artifacts. This is the merge gate.
2. **`llm-pr-review.yml`** — every non-draft PR: sends the PR diff +
   title/description to the OpenAI API via `.github/scripts/llm_ci.py` and
   posts ONE review comment (marker `<!-- llm-pr-review -->`, updated in
   place on new pushes — never a comment flood).
3. **`ci-failure-analysis.yml`** — fires when Android CI fails: feeds the
   failed-step logs to the LLM and comments a diagnosis (root cause +
   suggested fix) on the PR, or on the commit for direct pushes to `main`.

Both LLM workflows **skip gracefully** when the `OPENAI_API_KEY` secret is
absent (checked via a step output — job-level `if` cannot read secrets).

## Configuration (repo Settings → Secrets and variables → Actions)

| Name | Kind | Purpose |
|------|------|---------|
| `OPENAI_API_KEY` | secret, **required** for LLM features | auth for the OpenAI API |
| `OPENAI_MODEL` | variable, optional | model override; default `gpt-5-mini` (set in `llm_ci.py`) |
| `OPENAI_BASE_URL` | env, optional | point `llm_ci.py` at any OpenAI-compatible endpoint |

When changing review behavior (tone, priorities, language), edit the system
prompts in `llm_ci.py` — not the workflows. The review prompt encodes project
priorities (API-23 guards, bitmap hygiene) — keep it in sync with the
low-end-performance and android-compat skills.

## Gradle & local builds

- CI provisions **Gradle 8.9** via `gradle/actions/setup-gradle` and calls
  `gradle` (not `./gradlew`) — there is intentionally no committed wrapper
  jar yet. If you generate a wrapper (`gradle wrapper --gradle-version 8.9`),
  commit it AND switch the workflows to `./gradlew` in the same PR.
- AGP 8.7.x requires **JDK 17**. The dev machine's default JVM may be Java 8;
  build locally through Android Studio (bundled JBR 17) or set `JAVA_HOME` to
  a JDK 17 (e.g. `C:\Program Files\Android\Android Studio\jbr`).
- Local equivalents of the gate: `gradle testDebugUnitTest lintDebug` —
  run before pushing; CI should confirm, not discover.

## Conventions

- Work in `feature/<topic>` branches; PR into `main`; merge only with green
  Android CI. Treat the LLM review as a smart colleague: address or rebut
  each point in the PR conversation, don't silently ignore it — but it is
  advisory, not a merge gate.
- Unit tests accompany every piece of pure logic (queue, cache eviction,
  detectors, intervals). UI/animation code stays thin so it needs no tests.
- Lint `NewApi` findings are errors — fix the guard, never suppress.
- Dependabot PRs (weekly, Gradle + Actions): check the dependency's minSdk
  before merging — a routine bump that raises minSdk above 23 silently kills
  the product (see low-end-performance dependency policy).

## Extending (planned, keep in this order)

1. **Instrumented tests**: separate workflow job with
   `reactivecircus/android-emulator-runner`, API 23 + API 35 matrix,
   triggered on PRs labeled `needs-device-tests` (emulator minutes are
   expensive — opt-in, not default).
2. **Release**: tag `v*` → workflow builds a signed release APK (signing key
   as base64 secret `RELEASE_KEYSTORE` + `RELEASE_KEYSTORE_PASSWORD`),
   attaches it to a GitHub Release. Sideloading is the primary distribution
   for frames; Play Store later if ever.
