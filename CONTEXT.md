# kld launcher

An Android home launcher that shows a plain list of app names, most used first, plus a small dock of icons. Built to be as fast and as small as possible.

## Language

**App**:
One launcher entry, identified by its component. An installed package with two launcher entries is two Apps, and each can be docked separately.
_Avoid_: Application, package, shortcut

**App list**:
The scrolling list of app names, sorted by the chosen sort order (most used by default). Holds every App that is not in the Dock.
_Avoid_: Drawer, menu, home screen

**Dock**:
The fixed bar at the bottom of the screen. Shows icons only: the Settings button and up to four Docked apps.
_Avoid_: Taskbar, favourites, shelf

**Dock slot**:
One of the four positions in the Dock that can hold an App. Slots fill left to right with no gaps.
_Avoid_: Cell, pin

**Docked app**:
An App that currently lives in the Dock. An App is in the Dock or in the App list, never both.
_Avoid_: Pinned app, favourite

**Settings button**:
The permanent gear at the far left of the Dock. It is not an App and does not use a Dock slot.
_Avoid_: Gear icon, menu button

**Launch count**:
How many times an App has been opened from the launcher, from the App list or the Dock. Used to rank the App list.
_Avoid_: Usage, frequency, score
