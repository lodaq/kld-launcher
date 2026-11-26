# Workspace Rules

## Keep `STACK_AND_SKILLS.md` Up to Date

Always update [STACK_AND_SKILLS.md](./STACK_AND_SKILLS.md) in the same turn whenever you:

1. Install, remove, or update a project-local skill in `.agents/skills/` or `skills-lock.json`, or rely on a new global skill for work in this repository.
2. Change any part of the tech stack: Gradle/AGP versions, SDK versions (`minSdk`, `compileSdk`, `targetSdk`), Java version, R8/ProGuard settings, dependencies, manifest constraints, or core architectural rules.

## Feature Backlog & Design Constraints

* Check [CONTEXT.md](./CONTEXT.md), [DESIGN.md](./DESIGN.md), and [docs/adr/](./docs/adr/) before implementing changes.
* When finishing a feature listed in [FEATURES.md](./FEATURES.md), mark its checkbox `[x]` in the same turn.

## Device Testing & Verification

* Always build, install, and verify changes on the user's connected phone (for example via `adb` / `android` CLI) before considering a task complete.
* If no physical phone is connected or reachable, report that clearly to the user and do not claim runtime verification passed.
