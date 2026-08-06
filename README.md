# Pegasus Lucent

Lucent is a cinematic Pegasus Frontend theme and Android companion for handheld gaming devices. It supports conventional single-screen Android devices and dual-screen hardware such as the AYN Thor.

## Download

Install the single **Lucent unified APK** from the [latest release](https://github.com/wildonrio/pegasus-lucent/releases/latest). It contains Pegasus Frontend, the Lucent theme, the importer and media services, direct-launch bridges, updater, and the optional Thor Stop-button service. No separate Pegasus, theme ZIP, controller APK, or companion APK is required.

Lucent checks this repository at startup and can also check manually from Settings. When an update is available it asks before downloading and opens Android's standard installer confirmation; Android does not permit a normal third-party app to silently replace itself.

## What Lucent adds

- Installs and selects the Lucent theme without touching ROM files.
- Scans internal and removable-storage Downloads folders for newly downloaded games.
- Discovers existing libraries in common ROM and emulation folders.
- Imports identified games into the correct collection and removes a source archive only after a verified import.
- Retrieves box art, wallpaper, and preview media on demand.
- Supplies the lower-screen preview service used on dual-screen Android hardware.
- Checks GitHub for app and theme updates at startup and on manual request.
- Keeps games without box art out of the visual library while retaining them in the archive list for manual inclusion.

## Installation

1. Download and install `lucent-unified-<version>.apk` from the latest release.
2. Open Lucent and grant the storage permissions Android requests. The first library discovery runs automatically.
3. On an AYN Thor, enable **Pegasus Lucent Controller** in Android Accessibility settings if you want the one-second Stop-button shortcut.

On the Thor, a normal press of the square Stop/Select button is passed through unchanged as Select. Holding it continuously for one second closes the active game and restores Pegasus.

## Source layout

- `theme/` — Pegasus QML theme and artwork.
- `android-companion/` — dependency-free Android companion, importer, preview service, media enrichment, and updater.
- `android-launch-bridge/` — ROM intent bridge and the one-second hold-to-exit controller service.
- `unified-android/` — reproducible build that combines Pegasus and every Lucent component into the release APK.
- `release-manifest.json` — signed-artifact versions, stable release URLs, and SHA-256 checksums used by the updater.

## Privacy and safety

Lucent does not bundle games, firmware, keys, or emulator binaries. It does not download ROMs. Library discovery and metadata generation happen on the device. Source files are deleted from Downloads only after a game import is verified.

## License

Lucent's original code is released under the MIT License. Product names, platform logos, and other third-party artwork remain the property of their respective owners. Pegasus Frontend is a separate GPL-licensed project.
