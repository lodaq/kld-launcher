Build an Android home launcher called "kld launcher" (package app.kld.launcher). It is for personal use only: no store listing, no signing setup beyond the debug key, no backup. Speed and a tiny footprint are the top priorities. Native Android only.

Read these first, in order: CONTEXT.md (the vocabulary), DESIGN.md (every visual and behaviour rule), docs/adr/ (decisions you must not undo). DESIGN.md wins over your habits.

The folder project/ holds a reference implementation. Start from it. It has not been compiled: build it, fix whatever fails, and keep it this small. Do not add features, libraries, animation or extra screens.

WHAT TO BUILD
- App list: a scrolling list of app names, most launched first, ties alphabetical. Names only by default.
- Dock: a fixed bar at the bottom. It holds the Settings button (far left, permanent) and up to four Docked apps, shown as icons only.
- Drag and drop: long-press an app in the App list or the Dock to drag it. Drop onto a Dock slot to dock it, or onto the App list to undock it. Tap launches.
- An app is in the Dock or in the App list, never both. Dropping on an occupied slot swaps: the displaced app returns to the list, and two docked apps swap places. Dropping on the Settings button or a free area fills the leftmost free slot, or does nothing if the Dock is full.
- Launches from the Dock and from the list both increment the Launch count. An undocked app returns to its normal sorted place.
- Docked apps that get uninstalled disappear silently and the Dock shifts left.
- Settings screen (opened by the Settings button): "Show icons" (On or Off, default Off) adds icons beside names in the App list, and "Manage apps" opens the system app settings. Back returns to the list.
- First-run prompt: when launched from the old launcher and not yet the default Home app, ask the system to make kld launcher the default (see DESIGN.md, Default Home prompt).
- App icon: a white letter K on black.
- Home press scrolls the list to the top. Back does nothing on the list.

NOT in this build, planned for later: a "Show launch counts" setting, other sort modes, custom reordering. Counting itself stays in this build because it ranks the App list. Do not build them.

HARD CONSTRAINTS
- Java, not Kotlin. No AndroidX, no Compose, no third-party libraries. Zero dependencies.
- One Activity. Switch between the list screen and the Settings screen by swapping the content view.
- No services, receivers, threads or background work. No animation of any kind.
- Resource files: ic_settings.xml and the K icon files named in DESIGN.md. No layout XML.
- Dock icons load in onStart and are released in onStop. List icons load lazily for visible rows through a small bounded cache that exists only while Show icons is on.
- Manifest: a MAIN + HOME + DEFAULT filter and a separate MAIN + LAUNCHER filter, supportsRtl=true, singleTask, exported, allowBackup=false, and a queries block for MAIN/LAUNCHER. Do not use QUERY_ALL_PACKAGES.
- minSdk 24, targetSdk 35, compileSdk 35. Release build with R8 minify and shrinkResources on.

ACCEPTANCE CHECKS (report the result of each)
1. ./gradlew assembleRelease succeeds. Report the APK size.
2. Open kld launcher from the old launcher: the system asks to make it the default Home app. After accepting, pressing Home shows the list immediately and no prompt appears again.
3. Long-press an app in the list and drop it on the Dock: it leaves the list and appears as an icon in the Dock.
4. Drop it back on the list: it leaves the Dock and reappears in the list at its sorted place.
5. Fill the Dock with four apps, then drop a fifth onto an occupied slot: they swap, and the displaced app is back in the list. No app is ever visible in both places.
6. Drop one docked app onto another: their positions swap.
7. Uninstall a docked app: the Dock closes the gap with no message.
8. Show icons On: icons appear beside names. Turn it Off: they disappear.
9. Launch an app three times, press Home: it ranks above apps launched fewer times.
10. Change the phone language: app names update. Idle: no threads or wakeups beyond system defaults. Run `adb shell dumpsys meminfo app.kld.launcher` and report PSS with Show icons Off and On.

If any rule in DESIGN.md is impossible on Android, stop and say which one and why. Do not silently work around it.
