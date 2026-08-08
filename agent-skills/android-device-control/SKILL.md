---
name: android-device-control
description: Remotely inspect and operate Android phones, tablets, emulators, TVs, and other Android devices from Codex. Use when a user asks for a short-link, low-setup remote-support session through RustDesk; asks to connect to or navigate an Android device; wants Codex to see the current screen, tap, type, press keys, swipe, launch apps, or automate a visual Android workflow; or needs developer-grade ADB and scrcpy control over USB or wireless debugging.
---

# Android Device Control

Use the RustDesk quick-link flow for users who want minimal setup. Use `scripts/androidctl` for deterministic ADB control when debugging is already available.

## Quick-link mode

Use this mode by default when the user wants to tap one link and approve remote control without developer settings or a cable.

1. Give the Android user this short address: **`rustdesk.com`** ([clickable link](https://rustdesk.com/)).
2. Ask them to install/open the Android app, choose **Share Screen**, enable **Screen Capture** and **Input Control**, then tap **Start Service**. Android requires these system approvals; a browser alone cannot grant them.
3. Launch the Mac controller with `scripts/rustdeskctl launch`.
4. Connect to the Android device using the ID shown in its RustDesk screen, or use LAN discovery if it appears. Prefer manual acceptance on the Android device instead of asking the user to expose a temporary password in chat.
5. After the session opens, inspect and control the RustDesk window with the Mac screen, mouse, and keyboard tools. Re-check the visible phone screen after each consequential action.

Run `scripts/rustdeskctl status` to verify the controller installation. Read [references/quick-link.md](references/quick-link.md) if pairing or permissions fail.

## ADB first use

Run:

```bash
SKILL_DIR="/Users/tyleryoung/.codex/skills/android-device-control"
"$SKILL_DIR/scripts/androidctl" doctor
"$SKILL_DIR/scripts/androidctl" devices
```

If no authorized device appears, read [references/setup.md](references/setup.md). Never attempt to bypass a lock screen, debugging authorization prompt, work-profile policy, or other device security control.

Use `--serial SERIAL` on every command when more than one device is connected. `ANDROID_SERIAL` is also honored.

## Visual control loop

Operate like a person using a screen:

1. Capture fresh state with `androidctl observe`. This writes `screen.png`, `ui.xml`, and `state.json` to a timestamped directory and prints the paths.
2. View `screen.png` with the local image-viewing tool. Use `ui.xml` or `androidctl find QUERY` when semantic labels are useful.
3. Perform one or a small group of obvious actions.
4. Observe again and verify the result before continuing.

Screens change after every action. Do not reuse old coordinates after a transition, rotation, keyboard appearance, dialog, or scroll.

Prefer semantic targets when available:

```bash
androidctl find "Settings"
androidctl tap-text "Settings"
androidctl wait-text "Connected" --timeout 15
```

Fall back to screen coordinates when labels are absent:

```bash
androidctl tap 540 1700
androidctl long-press 540 1700 --duration 900
androidctl swipe 540 1700 540 500 --duration 450
androidctl text "hello@example.com"
androidctl key ENTER
```

`text` is reliable for ordinary ASCII. For non-ASCII text or complex editor behavior, launch `scrcpy` and paste/type through its window.

## Common controls

```bash
androidctl screenshot --output /tmp/android.png
androidctl ui --output /tmp/android-ui.xml
androidctl screen-size
androidctl key BACK
androidctl key HOME
androidctl key APP_SWITCH
androidctl wake
androidctl open-url "https://example.com"
androidctl launch com.android.settings
androidctl stop com.example.app
```

Run `androidctl --help` or `androidctl COMMAND --help` for the complete CLI.

## Live mirroring

For a continuously updating, human-operable window, run `androidctl mirror`. Use an execution session that can remain active. The window accepts mouse and keyboard input through scrcpy. Keep using `observe` for reliable visual checkpoints and machine-readable UI data.

## Wireless devices

Android 11 and later can pair using the device's Wireless debugging screen:

```bash
androidctl pair 192.168.1.25:37123 123456
androidctl connect 192.168.1.25:40117
androidctl devices
```

The pairing and connection ports may differ. Read [references/setup.md](references/setup.md) for the full USB and wireless setup.

## Safety and reliability

- Treat taps on send, buy, publish, delete, transfer, factory-reset, permission, and account-security controls as consequential actions. Obtain any authorization required by the user's request before committing them.
- Select the device by exact serial when multiple devices are present.
- Stop if the screenshot and UI hierarchy disagree about the active screen.
- Expect screenshots to be black for DRM-protected or `FLAG_SECURE` content; do not attempt to circumvent that protection.
- Expect `uiautomator` labels to be incomplete in games, canvases, videos, custom-rendered apps, and some WebViews. Use the screenshot in those cases.
- Do not infer that a tap worked. Re-observe and verify.
