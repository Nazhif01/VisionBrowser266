# Vision Browser 26.3

A client-side Fabric mod for Minecraft Java 26.3. Vision Browser embeds Chromium through Rinku and adds a floating synchronized-lyrics HUD.

## Features

- Embedded Chromium browser with navigation and tabs
- Floating lyrics overlay synchronized to playback in the active Vision Browser tab
- LRCLIB lookup for timestamped lyrics
- Current lyric line highlighted with neighboring lines visible
- Toggle the lyrics overlay with **J** (rebindable in Controls)
- World-space browser panel prototype is temporarily disabled while its renderer is being corrected

## Floating lyrics

The lyrics bridge reads the active media title, artist, playback state, and playback position from supported HTML audio/video pages opened inside Vision Browser. It requests timestamped lyrics from LRCLIB. Lyrics availability depends on the track having a synchronized entry in LRCLIB.

The overlay is independent of Dynamic Island and does not include music-control widgets.

## Build

GitHub Actions builds the project on pushes to `main`. Download the `VisionBrowser-26.3` artifact from the successful Actions run.

## Credits and licensing

This project is an independent implementation. Rinku is a separate LGPL-2.1-or-later dependency and is not bundled into this mod. Synchronized lyrics are retrieved from LRCLIB.
