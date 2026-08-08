# Android connection setup

## USB debugging

This Mac has the official Android SDK Platform-Tools installed at `~/.codex/tools/android-platform-tools`; `androidctl` discovers that copy even when `adb` is not on the shell `PATH`. Live mirroring uses the Homebrew `scrcpy` package.

1. On the Android device, open **Settings > About phone** and tap **Build number** seven times to enable Developer options.
2. Open **Settings > System > Developer options** (the path varies by manufacturer) and enable **USB debugging**.
3. Connect the device with a data-capable USB cable.
4. Unlock the device and accept the computer's RSA debugging prompt.
5. Run `androidctl devices`. The state must be `device`, not `unauthorized` or `offline`.

Some Xiaomi-family devices require the separate **USB debugging (Security settings)** option for injected input. Follow the device manufacturer's policy; do not bypass managed-device restrictions.

## Wireless debugging on Android 11+

1. Put the Mac and Android device on the same trusted network.
2. On the device, enable **Developer options > Wireless debugging**.
3. Choose **Pair device with pairing code** and note the displayed IP address, pairing port, and six-digit code.
4. Run `androidctl pair IP:PAIRING_PORT CODE`.
5. Return to the main Wireless debugging screen and note the current IP address and connection port.
6. Run `androidctl connect IP:CONNECTION_PORT`, then `androidctl devices`.

Pairing and connection ports commonly differ and may change when wireless debugging is restarted.

## Older TCP/IP workflow

When supported by the device, connect once over USB, run `adb tcpip 5555`, disconnect USB, then run `androidctl connect DEVICE_IP:5555`. Prefer Android 11+ pairing when available.

## Troubleshooting

- `unauthorized`: unlock the device and accept the RSA prompt. If needed, revoke USB debugging authorizations on the device and reconnect.
- `offline`: reconnect the cable or wireless session, then run `adb kill-server` and `adb start-server`.
- no device: try another data-capable cable/port, confirm USB mode and debugging, and run `androidctl doctor`.
- input rejected: check device-vendor security settings and managed-device policy.
- black screenshot: the app may use Android secure-window protection. That content is intentionally unavailable to ADB screenshots and scrcpy.
- multiple devices: add `--serial SERIAL` before the subcommand or set `ANDROID_SERIAL`.

## Upstream documentation

- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [SDK Platform-Tools downloads and release notes](https://developer.android.com/tools/releases/platform-tools)
- [scrcpy official project](https://github.com/Genymobile/scrcpy)
