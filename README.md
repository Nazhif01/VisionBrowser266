# Vision Browser 26.3

A client-side Fabric mod project for Minecraft Java 26.3. The goal is to create spatial browser windows inspired by Apple visionOS, using Rinku as the Chromium browser engine.

## Target features

- Floating browser windows in the Minecraft world
- Frosted-glass panels, rounded corners, and subtle rim lighting
- Drag, resize, and close controls
- Mouse, scroll, and keyboard input forwarded to the embedded browser

## Development status

**Milestone 0:** project scaffold and automated build pipeline.

The first build validates the Minecraft 26.3, Fabric, Java 25, and Rinku dependency setup. Spatial rendering and browser integration are subsequent milestones.

## Build

GitHub Actions builds the project on pushes to `main`. Download the `VisionBrowser-26.3` artifact from the Actions run.

## Credits and licensing

This project is an independent implementation. The spatial-window concept is inspired by visionOS. Rinku is a separate LGPL-2.1-or-later dependency and is not bundled into this mod.
