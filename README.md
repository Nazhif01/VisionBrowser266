# Vision Browser 26.3

A client-side Fabric mod for Minecraft Java 26.3. Vision Browser embeds Chromium through Rinku and provides spatial browser, floating-window, media-control, and synchronized-lyrics features.

## Features

- Embedded Chromium browser with navigation and tabs
- Browser-only media bridge for HTML audio/video pages opened in Vision Browser
- Spatial lyrics with individually animated world-space words, a 3D glow presentation, and a karaoke HUD option
- Enhanced LRC parsing, including per-word timestamps when supplied by the lyrics source
- LRCLIB exact lookup with a title/artist search fallback
- Album-art accent color, animated line transitions, and upcoming-line preview
- Dynamic Island with play/pause, previous/next, seek, mute, and volume controls
- Toggle the lyrics overlay with **J** and open Dynamic Island controls with **I**
- World-space browser panel prototype is temporarily disabled while its renderer is being corrected

## Lyrics architecture

The lyrics engine and renderer are original Vision Browser code. The media bridge reads title, artist, playback state, playback position, duration, volume, and artwork from supported HTML audio/video elements in Vision Browser tabs. Lyrics are looked up through LRCLIB and synchronized against the browser player's media clock.

The parser supports standard LRC timestamps, multiple timestamps on one line, offset metadata, and enhanced LRC word timestamps. Per-word karaoke highlighting appears when the returned lyrics contain word-level timing. Standard line-timed LRC remains supported.

The default 3D Words renderer places each lyric word as a separate vanilla world-space text object, spread across the scene with staggered heights, subtle floating motion, cyan/green/white accents, and word-by-word entrance. Enhanced LRC timestamps drive exact word timing when supplied. Standard LRC has line-level timing only, so VisionBrowser estimates word timing from each word's character length. The alternate 3D Glow renderer keeps the animated texture presentation, with Edit-style impact, pulse, fade-in, and wave presets, Chill/Normal/Hype intensity, album-accent or white tint, optional rainbow tint, and chromatic split at higher intensity. The optional HUD mode remains available.

Dynamic Island and Floating Window remain Vision Browser components. No Spotify or Windows system-media source is used.

## Build

GitHub Actions builds the project on pushes to `main` and pull requests targeting `main`. Download the `VisionBrowser-26.3` artifact from a successful Actions run.

## Credits and licensing

This project uses an independent lyrics implementation and does not copy Kangetsu Lyrics code or assets. Rinku is a separate LGPL-2.1-or-later dependency and is not bundled into this mod. Synchronized lyrics are retrieved from LRCLIB.


The default spatial presentation is **3D Words**. Select it in the chat overlay under Lyrics → Style.
