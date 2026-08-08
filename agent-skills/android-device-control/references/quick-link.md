# Quick-link remote support

## User flow

Give the user only this easy-to-type address:

**rustdesk.com**

On Android 6 or newer:

1. On `rustdesk.com`, choose **Download**, then the **Android Universal** build. Open the downloaded APK and approve **Install unknown apps / Allow from this source** if Android asks.
2. Open **Share Screen**.
3. Approve **Screen Capture**.
4. Enable **Input Control**. Android opens the Accessibility settings; choose **RustDesk Input** and enable it.
5. Tap **Start Service** and leave RustDesk running.
6. Tell Codex the displayed device ID, or allow Codex to look for the device through LAN discovery.
7. Approve the incoming connection on the phone when prompted.

Prefer connection approval over sharing the temporary password in chat. The Android user can stop the service or disconnect the session at any time.

## Android restrictions

A web page cannot control the rest of an Android device. Screen sharing requires Android's MediaProjection consent, and injected touch gestures require an enabled AccessibilityService. Recent Android versions may require screen-capture consent again for each new session. Do not attempt to automate or bypass these system approvals.

Some Android 13+ devices initially block accessibility for apps installed from outside an app store. If **RustDesk Input** is unavailable, open RustDesk's Android **App info**, use the top-right menu, choose **Allow restricted settings**, and then return to Accessibility. Do this only for the official RustDesk package the user intentionally downloaded.

## Controller workflow

Run:

```bash
SKILL_DIR="/Users/tyleryoung/.codex/skills/android-device-control"
"$SKILL_DIR/scripts/rustdeskctl" status
"$SKILL_DIR/scripts/rustdeskctl" launch
```

Use the RustDesk Mac window to enter the Android device ID or select a discovered peer. Once the phone accepts, use Mac visual-control tools against the remote-session window.

## Sources

- [RustDesk Android instructions](https://rustdesk.com/docs/en/client/android/)
- [Android AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 14 MediaProjection consent behavior](https://developer.android.com/about/versions/14/behavior-changes-14#media-projection)
