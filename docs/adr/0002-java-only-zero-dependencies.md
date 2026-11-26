# Java only, zero dependencies

The launcher must start fast, hold almost no memory and have a tiny APK. We use plain Java with the Android framework only: no Kotlin, AndroidX, Compose or third-party libraries. Each of those adds classes, startup work and size that a list of names does not need.

## Consequences
Some APIs are older or deprecated (for example `onBackPressed` and the system window inset getters). That is deliberate: they work on every target version and cost nothing. Do not "modernise" them by adding a library.
