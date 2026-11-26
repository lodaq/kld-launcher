# kld launcher — Feature Backlog

Mark a task `[x]` when implemented and verified against [DESIGN.md](./DESIGN.md) and [docs/adr/](./docs/adr/).
Each checkbox is a single, standalone feature task.

## App Visibility & Profiles
- [x] **Hide apps**: Allow hiding selected Apps from the App list, with a Settings screen list to view and unhide them.
- [x] **Show clones**: Support discovering and launching cloned / secondary-profile Apps (Android Work Profiles via Shelter/Island and Android 14+ Clone profiles via `LauncherApps` and `UserManager`) with a Settings toggle and clear distinction from the primary profile App.
- [ ] **Rename apps**: Allow giving any App a custom display label in the App list (with reset to system label), persisted locally.

## Finding & Launching Apps
- [ ] **Type-to-filter search**: Add a minimal text filter input (or toggleable search row) to narrow the App list by name as you type, with no animation.
- [ ] **Auto-launch single search match**: Add a Settings toggle (default Off) to launch the App automatically when search narrows the list to one match.
- [ ] **A–Z quick index**: Add a tap-to-jump letter strip along the side when sorted A to Z so rarely used Apps take one tap to reach.
- [ ] **Keyboard / D-pad launch**: Support hardware keyboard typing to filter and Enter to launch the top App.

## Sorting & Ranking
- [ ] **Show launch counts**: Add a "Show launch counts" setting (On/Off, default Off) showing each App's Launch count on the right of its row in secondary text color.
- [ ] **Sort modes**: Add a Settings option to switch App list order between Most used (default), A to Z, Recently used, Recently installed, and Custom.
- [ ] **Custom app reordering**: Add Up and Down controls when sort mode is Custom so the App list order can be arranged manually.
- [ ] **Reset launch counts**: Add a Settings action to zero all Launch counts without resetting the Dock or other preferences.

## Dock & Layout
- [ ] **Lock dock**: Add a Settings toggle to disable Dock sorting and pin/unpin changes so Docked apps cannot be moved accidentally.
- [ ] **Configurable dock slots**: Allow choosing Dock capacity (e.g. 0, 2, 3, 4, or 5 slots, default 4) in Settings.
- [ ] **List density**: Add a Settings option to switch row spacing/text size between Default and Compact.
- [ ] **Fullscreen / status bar toggle**: Add a Settings toggle to show or hide the system status bar.

## System & Maintenance
- [ ] **Optimize icon loading / scrolling**: Improve performance and eliminate scroll lag when `Show icons` is On (e.g. avoid synchronous main-thread `PackageManager.getActivityIcon()` / bitmap decoding during `ListView` scrolling).
- [ ] **Export / import settings**: Add Settings actions to export and restore Dock, hidden apps, custom names, Launch counts, and toggles using Android's system document picker (since `allowBackup` is disabled).
- [ ] **Phone performance boost**: Add a button inside Settings to improve the performance of the phone (within Android's unprivileged launcher constraints, e.g. clearing launcher caches or jumping to system device-care / running-services settings).
