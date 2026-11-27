# DESIGN.md: kld launcher design system

Principle: the launcher is a list of words and a row of icons. Anything that does not help someone find and open an app is removed. When in doubt, leave it out. Use the words in CONTEXT.md.

## Hard budget
- Zero dependencies. Java only. No AndroidX, no Kotlin.
- No animation of any kind: no transitions, no ripple, no fades, no layout animation.
- Icons exist only in the Dock, and in the App list when Show icons is On (default Off).
- No services, receivers, or idle background work. At idle the app does nothing; transient short-lived worker threads run only to rasterize icons when loading/toggling `Show icons`, to query today's app usage when `Show time today` is On or `App limits` are set, or to close previous background processes when `Close previous app` is On.

## Colour (ARGB constants at the top of MainActivity)
- BACKGROUND 0xFF000000 (true black)
- TEXT 0xFFFFFFFF
- TEXT_SECONDARY 0xFF8A8A8A (values, hints, the Settings button)
- DIVIDER 0xFF222222
No accent colour, no gradients, no transparency.

## Typography
System default font only. Never bundle a font.
- APP_NAME: 20sp, white, one line, end ellipsis
- SCREEN_TITLE and settings labels: 18sp, white
- SECONDARY: 14sp, TEXT_SECONDARY
No bold, italic, letter spacing or all caps.

## Spacing (dp)
- Base unit 16. Row padding 16 on all sides, so a row is about 56dp tall.
- Minimum touch target 48dp.
- Rows run edge to edge. System bar insets become padding on the screen root.

## Components
- App row: left-aligned app name (`APP_NAME`, or `TEXT_SECONDARY` with dimmed row opacity when blocked by a daily limit; blocked apps sort to the bottom of the App list). With Show icons On, a 32dp icon sits on the start side with 16dp spacing before the name. With Show time today On (and Usage Access granted), today's foreground time sits on the end side in `SECONDARY` (hidden when `<1s`). Apps with a daily limit show `used / limit` (or `Blocked` once reached) in `SECONDARY`.
- Scrollbar: a 4dp thumb on the end edge (`TEXT_SECONDARY` idle, `TEXT` while dragged) inside a 28dp touch strip; dragging it up or down scrolls the list smoothly by pixel offset (`setSelectionFromTop`).
- Dock: 56dp tall, above a single 1dp DIVIDER line. Six equal-width cells: the Settings button and five Dock slots. Nothing scrolls.
- Dock slot: an icon only, 40dp inside the 56dp bar, no label (dimmed when blocked by a daily limit). The icon's accessibility description is the app name. An empty slot is blank.
- Settings button: outline gear, 24dp drawing, stroke only, TEXT_SECONDARY. It is one VectorDrawable (res/drawable/ic_settings.xml). It is not an App and does not use a Dock slot. Its position in the Dock can be changed via Sort dock.
- Settings screen: a title row, then one row per setting, each separated by a DIVIDER line. Label on the left, value on the right in SECONDARY. Toggles are the words On and Off, not switch widgets. Tapping a row changes it in place.
- Settings rows in this build: "Show icons" (On or Off, default Off), "Show time today" (On or Off, default Off; requests Android Usage Access when toggled On without permission and shows today's foreground duration per App), "Show clones" (On or Off, default Off; discovers Android Work Profiles and Android 14+ Clone Profiles), "Close previous app" (On or Off, default Off; calls ActivityManager.killBackgroundProcesses on other launcher packages when launching an app, effective on Android <= 13), "Hidden apps" (opens a list of Apps to hide or unhide), "App limits" (requires device owner verification via biometric or device credential when a screen lock is set, then opens a list of primary Apps to set a daily usage limit via Usage Access), "Sort order" ("Most used" or "A to Z", default "Most used"), "Sort dock" (reorders the Settings button and Docked apps), "Manage apps" (opens the system app settings), and "Phone performance boost" (trims background processes of other launcher packages on a short-lived worker thread).
- App icon: a white letter K on black, drawn as vectors: res/drawable/ic_launcher.xml, ic_launcher_fg.xml and the adaptive res/drawable-anydpi-v26/ic_launcher.xml. These and ic_settings.xml are the only resource files.

## Behaviour
- Tap an App to launch it. Launch count goes up by one, from the list or the Dock. If a daily limit is reached, tapping the App or its shortcuts does not launch it.
- Long-press an App (list or Dock): opens the App options screen showing up to five enabled app shortcuts (from LauncherApps.getShortcuts on Android 7.1+ when the launcher holds shortcut host permission, omitted when blocked), "Pin to dock" (or "Unpin from dock" + "Move left" / "Move right" when docked), "Daily limit" (for primary Apps), "Rename", "App info", and "Uninstall" (for removable apps).
- Long-press the Settings button (or tap "Sort dock" in Settings): opens the Sort dock screen with Up and Down controls to reorder the Settings button and Docked apps across the six Dock cells.
- An App is in the Dock or in the App list, never both.
- Dock capacity is five Apps (six cells total including the Settings button). Slots fill left to right with no gaps.
- A docked App that is uninstalled is dropped silently and the Dock shifts left.
- Home press: scroll the list to the top with setSelection(0). Never smooth scroll. If any sub-screen (Settings, Hidden apps, App limits, App limit, Sort dock, App options, or Rename) is open, return to the list.
- Back: does nothing on the list. On Settings or App options it returns to the list; on Hidden apps or App limits it returns to Settings; on App limit or Rename it returns to App limits or App options; on Sort dock it returns to Settings (or the list if opened from the Settings button).
- Default Home prompt: on a cold start, if kld launcher is not the default Home app, show the system prompt to make it so (RoleManager on Android 10 and later, the Home settings screen before that). Nothing is shown once it is the default. The manifest also has a LAUNCHER filter so the app can be opened once from the old launcher.
- No empty states, toasts or dialogs. Swallow errors silently.

## Memory rules for icons
- Dock icons (at most five) and List icons (when Show icons is On) are pre-rasterized to `40dp` / `32dp` bitmaps on a short-lived worker thread so `getView` and app launch never block the UI thread.
- List icons use a cache sized for all installed apps so fast scrolling never hits IPC cache misses.
- Caches stay warm across normal app switches for zero-latency Home return and are released under OS memory pressure (`onTrimMemory`) or when Show icons is turned Off.

## Code rules
- One Activity. Switch screens by swapping the content view.
- Reuse views through convertView. Never inflate XML. Allocate nothing in getView after the first rows exist.
- SharedPreferences writes use apply(), never commit().
- The label cache is cleared when the system language changes.
- Right-to-left languages are supported (supportsRtl is on): use start and end APIs such as setCompoundDrawablesRelative, never left and right.
- Every new feature is checked against the Hard budget. If it costs memory, a thread or a dependency, it is off by default and loads lazily.

## Not in this build
Planned for later, do not build yet: a "Show launch counts" setting (the count on the right of each row in SECONDARY). Counting itself is in this build: the Launch count ranks the App list when Sort order is "Most used".
