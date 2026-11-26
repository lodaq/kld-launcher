# Long-press drags apps; App info moves to Settings

Moving apps between the App list and the Dock needs a gesture, and a scrolling list leaves long-press as the only clean one. Long-press used to open App info (the route to uninstall), so we gave long-press to dragging and moved App info to a "Manage apps" row in Settings that opens the system app list.

## Considered Options
- Long-press opens a menu with "Move to Dock" and "App info": more code and another screen, against the no-fancy-UI goal.
- A separate drag handle on each row: clutters a names-only list.

## Consequences
Uninstalling an app takes two taps from Settings instead of one long-press. Do not restore long-press App info.
