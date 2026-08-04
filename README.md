# Pegasus Lucent

Lucent is a cinematic Pegasus Frontend theme and Android companion for handheld gaming devices. It supports conventional single-screen Android devices and dual-screen hardware such as the AYN Thor.

## Downloads

- **Pegasus Lucent APK:** installs the companion service and the bundled Lucent theme.
- **Lucent theme ZIP:** installs the theme by itself for an existing Pegasus setup.

Use the assets attached to the [latest release](https://github.com/wildonrio/pegasus-lucent/releases/latest). The APK checks this repository when its service starts. Theme updates are applied inside Lucent; Android displays its normal confirmation screen before an APK update is installed.

## What the companion adds

- Installs and selects the Lucent theme without touching ROM files.
- Scans internal and removable-storage Downloads folders for newly downloaded games.
- Discovers existing libraries in common ROM and emulation folders.
- Imports identified games into the correct collection and removes a source archive only after a verified import.
- Retrieves box art, wallpaper, and preview media on demand.
- Supplies the lower-screen preview service used on dual-screen Android hardware.
- Checks GitHub for app and theme updates at startup and on manual request.
- Keeps games without box art out of the visual library while retaining them in the archive list for manual inclusion.

## Installation

1. Install [Pegasus Frontend](https://pegasus-frontend.org/) if it is not already installed.
2. Install `pegasus-lucent.apk` from the latest release.
3. Open **Pegasus Lucent** once and grant the storage and display permissions Android requests.
4. Open Pegasus. Lucent is installed under `pegasus-frontend/themes/lucent` and selected automatically.

The standalone ZIP contains a top-level `lucent` folder. Extract it into `pegasus-frontend/themes/` if you only want the theme.

## Source layout

- `theme/` — Pegasus QML theme and artwork.
- `android-companion/` — dependency-free Android companion, importer, preview service, media enrichment, and updater.
- `release-manifest.json` — signed-artifact versions, stable release URLs, and SHA-256 checksums used by the updater.

## Privacy and safety

Lucent does not bundle games, firmware, keys, or emulator binaries. It does not download ROMs. Library discovery and metadata generation happen on the device. Source files are deleted from Downloads only after a game import is verified.

## License

Lucent's original code is released under the MIT License. Product names, platform logos, and other third-party artwork remain the property of their respective owners. Pegasus Frontend is a separate GPL-licensed project.
