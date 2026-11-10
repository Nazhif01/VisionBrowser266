# Vision Browser 26.3

A client-side Fabric mod for Minecraft Java 26.3. The goal is to create spatial browser windows inspired by Apple visionOS, using Rinku as the Chromium browser engine.

## Target features

- True world-space floating browser windows
- Frosted-glass panels, rounded corners, and subtle rim lighting
- Grab, move, resize, and close windows in 3D space
- Mouse, scroll, and keyboard input forwarded to the embedded browser
- Multiple independent browser windows and tabs

## Development status

- **Milestone 0: Build and browser foundation.** Fabric 26.3, Java 25, Rinku setup, browser screen, navigation, tabs, and browser input.
- **Milestone 1: World-space window prototype.** Next: render a browser texture as a panel in the Minecraft world, anchor it in front of the player, and route ray-picked mouse input into the browser.
- **Milestone 2: Spatial interaction and visual polish.** Grab and move panels, resize them in 3D, close controls, frosted-glass styling, and multiple panels.

Dynamic Island and music-control work has been removed to keep development focused on the spatial browser.

## Build

GitHub Actions builds the project on pushes to `main`. Download the `VisionBrowser-26.3` artifact from the Actions run.

## Credits and licensing

This project is an independent implementation. The spatial-window concept is inspired by visionOS. Rinku is a separate LGPL-2.1-or-later dependency and is not bundled into this mod.
