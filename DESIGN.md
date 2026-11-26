# DESIGN.md: kld launcher design system

Principle: the launcher is a list of words and a row of icons. Anything that does not help someone find and open an app is removed. When in doubt, leave it out. Use the words in CONTEXT.md.

## Hard budget
- Zero dependencies. Java only. No AndroidX, no Kotlin.
- No animation of any kind: no transitions, no ripple, no fades, no layout animation.
- Icons exist only in the Dock, and in the App list when Show icons is On (default Off).
- No threads, services, receivers or background work. At idle the app does nothing.

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
- App row: one TextView, left aligned. With Show icons On, a 32dp icon sits on the left with 16dp spacing before the name.
- Dock: 56dp tall, above a single 1dp DIVIDER line. Six equal-width cells: the Settings button and five Dock slots. Nothing scrolls.
- Dock slot: an icon only, 40dp inside the 56dp bar, no label. The icon's accessibility description is the app name. An empty slot is blank.
- Settings button: outline gear, 24dp drawing, stroke only, TEXT_SECONDARY. It is one VectorDrawable (res/drawable/ic_settings.xml). It is not an App and does not use a Dock slot. Its position in the Dock can be changed via Sort dock.
- Settings screen: a title row, then one row per setting, each separated by a DIVIDER line. Label on the left, value on the right in SECONDARY. Toggles are the words On and Off, not switch widgets. Tapping a row changes it in place.
- Settings rows in this build: "Show icons" (On or Off, default Off), "Show clones" (On or Off, default Off; discovers Android Work Profiles and Android 14+ Clone Profiles), "Close previous app" (On or Off, default Off; calls ActivityManager.killBackgroundProcesses on other launcher packages when launching an app, effective on Android <= 13), "Hidden apps" (opens a list of Apps to hide or unhide), "Sort dock" (reorders the Settings button and Docked apps), and "Manage apps" (opens the system app settings).
- App icon: a white letter K on black, drawn as vectors: res/drawable/ic_launcher.xml, ic_launcher_fg.xml and the adaptive res/drawable-anydpi-v26/ic_launcher.xml. These and ic_settings.xml are the only resource files.

## Behaviour
- Tap an App to launch it. Launch count goes up by one, from the list or the Dock.
- Long-press an App (list or Dock): opens the App options screen showing up to five enabled app shortcuts (from LauncherApps.getShortcuts on Android 7.1+ when the launcher holds shortcut host permission), "Pin to dock" (or "Unpin from dock" + "Move left" / "Move right" when docked), "App info", and "Uninstall" (for removable apps).
- Long-press the Settings button (or tap "Sort dock" in Settings): opens the Sort dock screen with Up and Down controls to reorder the Settings button and Docked apps across the six Dock cells.
- An App is in the Dock or in the App list, never both.
- Dock capacity is five Apps (six cells total including the Settings button). Slots fill left to right with no gaps.
- A docked App that is uninstalled is dropped silently and the Dock shifts left.
- Home press: scroll the list to the top with setSelection(0). Never smooth scroll. If any sub-screen (Settings, Hidden apps, Sort dock, or App options) is open, return to the list.
- Back: does nothing on the list. On Settings or App options it returns to the list; on Hidden apps it returns to Settings; on Sort dock it returns to Settings (or the list if opened from the Settings button).
- Default Home prompt: on a cold start, if kld launcher is not the default Home app, show the system prompt to make it so (RoleManager on Android 10 and later, the Home settings screen before that). Nothing is shown once it is the default. The manifest also has a LAUNCHER filter so the app can be opened once from the old launcher.
- No empty states, toasts or dialogs. Swallow errors silently.

## Memory rules for icons
- Dock icons load in onStart (at most five) and are released in onStop.
- List icons load only for visible rows, through a bounded cache (32 entries) that exists only while Show icons is On and is released in onStop.
- Never hold icons while the launcher is hidden.

## Code rules
- One Activity. Switch screens by swapping the content view.
- Reuse views through convertView. Never inflate XML. Allocate nothing in getView after the first rows exist.
- SharedPreferences writes use apply(), never commit().
- The label cache is cleared when the system language changes.
- Right-to-left languages are supported (supportsRtl is on): use start and end APIs such as setCompoundDrawablesRelative, never left and right.
- Every new feature is checked against the Hard budget. If it costs memory, a thread or a dependency, it is off by default and loads lazily.

## Not in this build
Planned for later, do not build yet: a "Show launch counts" setting (the count on the right of each row in SECONDARY), sort modes (A to Z, recently used, custom) and custom reordering with Up and Down buttons. Counting itself is in this build: the Launch count ranks the App list.
