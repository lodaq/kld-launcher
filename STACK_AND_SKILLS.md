# Tech Stack & Skills

This document tracks the exact technology stack and agent skills used in `kld-launcher`. Keep it updated whenever dependencies, SDK/build versions, architecture rules, or installed skills change.

## Tech Stack

* **Language:** Java 17 (`JavaVersion.VERSION_17`), no Kotlin
* **UI & Platform APIs:** Pure Android Framework (`Activity`, `ActivityManager`, `AppOpsManager`, `UsageStatsManager`, `UsageEvents`, `ListView`, `BaseAdapter`, `LinearLayout`, `TextView`, `ImageView`, `LruCache`, `RoleManager`, `PackageManager`, `LauncherApps`, `ShortcutInfo`, `UserManager`, `SharedPreferences`) with programmatic views (no layout XML) and VectorDrawables (`ic_launcher.xml`, `ic_launcher_fg.xml`, `ic_settings.xml`)
* **Dependencies:** Zero third-party libraries, zero AndroidX, zero Jetpack Compose (enforced by [docs/adr/0002-java-only-zero-dependencies.md](./docs/adr/0002-java-only-zero-dependencies.md))
* **Build System:** Gradle `9.6.0` (Kotlin DSL), Android Gradle Plugin `9.5.3`
* **Android SDK Targets:** `compileSdk 35`, `minSdk 24`, `targetSdk 35`
* **Build Flags & Release Optimization:**
  * `optimization { enable = true }` (R8 code shrinking & optimization, optimized resource shrinking, and `proguard-android-optimize.txt`)
  * `proguardFiles("proguard-rules.pro")`
  * `buildConfig = false`
  * `android.nonTransitiveRClass=true`
  * `android.builtInKotlin=false` with `android.sync.suppressAgpWarnings=UNSUPPORTED_PROJECT_OPTION_USE` (preserves Java-only / zero-Kotlin APK per [ADR-0002](./docs/adr/0002-java-only-zero-dependencies.md))
  * `org.gradle.jvmargs=-Xmx2g`
  * [app/proguard-rules.pro](./app/proguard-rules.pro) (empty)
* **Runtime Architecture & Constraints:**
  * Single `Activity` ([MainActivity.java](./app/src/main/java/app/kld/launcher/MainActivity.java), `launchMode="singleTask"`, `stateNotNeeded="true"`)
  * `allowBackup="false"`, `supportsRtl="true"`, `Theme.Black.NoTitleBar`, `KILL_BACKGROUND_PROCESSES` (used only when `Close previous app` is On), `PACKAGE_USAGE_STATS` (used only when `Show time today` is On or `App limits` are set)
  * No services, broadcast receivers, or animations; at idle there is zero background work (short-lived worker threads run only transiently during icon pre-loading, today's usage query when `Show time today` is On or `App limits` are set, or `Close previous app`) ([DESIGN.md](./DESIGN.md))

---

## Skills Used

### Project-Local Skills (`.agents/skills/`, tracked in `skills-lock.json`)

| Skill | Source | Purpose in `kld-launcher` |
| :--- | :--- | :--- |
| [r8-analyzer](./.agents/skills/r8-analyzer/SKILL.md) | `android/skills` | Audits Gradle config and R8/ProGuard rules to keep release APK size minimal |
| [android-profiler](./.agents/skills/android-profiler/SKILL.md) | `android/skills` | Trace-backed CPU, memory, startup, and frame profiling (Perfetto / Simpleperf) |
| [anr-audit-and-fix](./.agents/skills/anr-audit-and-fix/SKILL.md) | `android/skills` | Audits main-thread work and ANR risks |
| [android-cli](./.agents/skills/android-cli/SKILL.md) | `android/skills` | Inspects built APKs/AABs, manages devices/emulators, and checks official docs |
| [android-intent-security](./.agents/skills/android-intent-security/SKILL.md) | `android/skills` | Audits `AndroidManifest.xml` exported components and `Intent` handling |
| [gradle-run](./.agents/skills/gradle-run/SKILL.md) | `chrisbanes/skills` | Runs `./gradlew` builds/checks with compact output summaries |
| [simplify-changes](./.agents/skills/simplify-changes/SKILL.md) | `chrisbanes/skills` | Reviews diffs for behavior-preserving simplifications |
| [android-benchmark-comparison](./.agents/skills/android-benchmark-comparison/SKILL.md) | `chrisbanes/skills` | Compares physical-device benchmark configurations when measuring changes |

### Global Skills Used in Workflow

* `find-skills` — Discovered and vetted Android/Java skills for this repository
* `deslop`, `principle-subtract-before-you-add`, `safe-refactor`, `tech-debt`, `domain-modeling`, `grill-me` — Available globally in `~/.agents/skills/` for keeping code minimal and clean
