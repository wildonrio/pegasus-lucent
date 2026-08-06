#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$PROJECT_DIR/.." && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION:-36.0.0}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-36}
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_DIR/platforms/$ANDROID_PLATFORM/android.jar"
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
APKTOOL=${APKTOOL:-/opt/homebrew/bin/apktool}
BUILD_DIR="$PROJECT_DIR/build"
VERSION_NAME=3.1.1
VERSION_CODE=73
BASE_NAME=pegasus-fe_alpha16-105-g6b322063_android64.apk
BASE_URL="https://raw.githubusercontent.com/mmatyas/pegasus-deploy-staging/continuous-android64/$BASE_NAME"
BASE_SHA256=e595be198bfd21c1855eaf563d5af0deae9c9601e6efb195ed299f2065287c67
BASE_APK=${PEGASUS_BASE_APK:-$BUILD_DIR/$BASE_NAME}

export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

rm -rf "$BUILD_DIR/work" "$BUILD_DIR/classes" "$BUILD_DIR/stub-classes" \
    "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/work" "$BUILD_DIR/classes" "$BUILD_DIR/stub-classes" \
    "$BUILD_DIR/dex"

if [ ! -f "$BASE_APK" ]; then
    mkdir -p "$(dirname "$BASE_APK")"
    curl -fL "$BASE_URL" -o "$BASE_APK.partial"
    mv "$BASE_APK.partial" "$BASE_APK"
fi
ACTUAL_BASE_SHA=$(shasum -a 256 "$BASE_APK" | awk '{print $1}')
if [ "$ACTUAL_BASE_SHA" != "$BASE_SHA256" ]; then
    printf 'Unexpected Pegasus base checksum: %s\n' "$ACTUAL_BASE_SHA" >&2
    exit 1
fi

# Build the exact theme delivered by the unified package.
THEME_ARCHIVE="$ROOT_DIR/android-companion/assets/pegasus-lucent-theme.zip"
rm -f "$THEME_ARCHIVE.partial.zip"
(cd "$ROOT_DIR/theme" && /usr/bin/zip -q -r "$THEME_ARCHIVE.partial.zip" .)
mv "$THEME_ARCHIVE.partial.zip" "$THEME_ARCHIVE"

DECODED="$BUILD_DIR/work/apk"
"$APKTOOL" d -f "$BASE_APK" -o "$DECODED" >/dev/null

# Keep Pegasus's JNI class names, but make Lucent the Android package and the
# only launcher. The package intentionally matches the existing companion so
# this unified build installs in place without deleting its settings.
perl -0pi -e 's/package="org\.pegasus_frontend\.android"/package="com.thorium.preview"/g;
    s/android:name="org\.qtproject\.qt5\.android\.bindings\.QtApplication"/android:name="com.thorium.preview.LucentApplication"/g;
    s/android:label="Pegasus"/android:label="Lucent"/g;
    s/org\.pegasus_frontend\.android\.files/com.thorium.preview.files/g' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e 's#android:icon="[^"]+"#android:icon="\@drawable/lucent_icon"#' \
    "$DECODED/AndroidManifest.xml"
perl -0pi -e "s/versionCode: .*/versionCode: $VERSION_CODE/; s/versionName: .*/versionName: $VERSION_NAME/" \
    "$DECODED/apktool.yml"
perl -0pi -e 's/org\.pegasus_frontend\.android\.files/com.thorium.preview.files/g;
    s/"org\.pegasus_frontend\.android"/"com.thorium.preview"/g' \
    "$DECODED/smali/org/pegasus_frontend/android/MainActivity.smali" \
    "$DECODED/smali/org/pegasus_frontend/android/BuildConfig.smali"

MANIFEST_COMPONENTS="$BUILD_DIR/work/manifest-components.xml"
printf '%s\n' \
'        <activity android:name="com.thorium.preview.PreviewActivity" android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" android:excludeFromRecents="true" android:launchMode="singleTop" android:resizeableActivity="true" android:screenOrientation="landscape" android:taskAffinity="com.thorium.preview.preview" android:exported="true"/>' \
'        <activity android:name="com.thorium.preview.RomLaunchActivity" android:excludeFromRecents="true" android:exported="true"/>' \
'        <activity android:name="com.thorium.preview.BrowserActivity" android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode" android:exported="false"/>' \
'        <service android:name="com.thorium.preview.PreviewService" android:exported="true"/>' \
'        <provider android:name="com.thorium.preview.UpdateFileProvider" android:authorities="com.thorium.preview.updates" android:exported="false" android:grantUriPermissions="true"/>' \
'        <provider android:name="com.thorium.preview.RomFileProvider" android:authorities="com.thorium.preview.roms" android:exported="false" android:grantUriPermissions="true"/>' \
'        <receiver android:name="com.thorium.preview.BootReceiver" android:enabled="true" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/></intent-filter></receiver>' \
'        <provider android:name="com.thorium.launchbridge.RomFileProvider" android:authorities="com.thorium.preview.launchbridge.roms" android:exported="false" android:grantUriPermissions="true"/>' \
'        <service android:name="com.thorium.launchbridge.StopButtonService" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE" android:exported="true"><intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter><meta-data android:name="android.accessibilityservice" android:resource="\@xml/stop_button_service"/></service>' \
'        <activity android:name="com.thorium.launchbridge.LaunchActivity" android:excludeFromRecents="true" android:launchMode="singleTask" android:theme="\@android:style/Theme.Translucent.NoTitleBar" android:exported="true"><intent-filter><action android:name="com.thorium.launchbridge.LAUNCH_FILE"/><category android:name="android.intent.category.DEFAULT"/></intent-filter><intent-filter><action android:name="com.thorium.launchbridge.SETUP_ACCESS"/><category android:name="android.intent.category.DEFAULT"/></intent-filter></activity>' \
    > "$MANIFEST_COMPONENTS"
COMPONENTS=$(sed 's/[&/]/\\&/g' "$MANIFEST_COMPONENTS" | tr '\n' ' ')
perl -0pi -e "s#</application>#$COMPONENTS</application>#" "$DECODED/AndroidManifest.xml"
perl -0pi -e 's#<application#<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/><uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/><uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><uses-permission android:name="android.permission.KILL_BACKGROUND_PROCESSES"/><uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/><application#' \
    "$DECODED/AndroidManifest.xml"

mkdir -p "$DECODED/res/raw" "$DECODED/res/xml" "$DECODED/res/drawable" "$DECODED/assets"
cp "$ROOT_DIR/android-companion/res/raw/"* "$DECODED/res/raw/"
cp "$ROOT_DIR/android-launch-bridge/res/xml/stop_button_service.xml" "$DECODED/res/xml/"
cp "$PROJECT_DIR/res/drawable/lucent_icon.xml" "$DECODED/res/drawable/"
cp "$THEME_ARCHIVE" "$ROOT_DIR/android-companion/assets/pegasus-lucent-version.txt" \
    "$DECODED/assets/"
cp "$ROOT_DIR/THIRD_PARTY_NOTICES.md" "$DECODED/assets/THIRD_PARTY_NOTICES.md"

# Compile all Java services together. The QtApplication stub is compile-only;
# the real superclass remains in Pegasus's primary classes.dex.
SOURCES=$(find "$ROOT_DIR/android-companion/src" "$ROOT_DIR/android-launch-bridge/src" \
    "$PROJECT_DIR/src" "$PROJECT_DIR/stubs" -name '*.java' -print)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" -d "$BUILD_DIR/classes" $SOURCES
mkdir -p "$BUILD_DIR/stub-classes/org/qtproject/qt5/android/bindings"
mv "$BUILD_DIR/classes/org/qtproject/qt5/android/bindings/QtApplication.class" \
    "$BUILD_DIR/stub-classes/org/qtproject/qt5/android/bindings/"

# The controller's standalone authority and return target differ only when its
# classes live inside Lucent. Patch generated bytecode sources before D8.
LAUNCH_SRC="$ROOT_DIR/android-launch-bridge/src/com/thorium/launchbridge/LaunchActivity.java"
STOP_SRC="$ROOT_DIR/android-launch-bridge/src/com/thorium/launchbridge/StopButtonService.java"
LAUNCH_CLASS="$BUILD_DIR/classes/com/thorium/launchbridge/LaunchActivity.class"
STOP_CLASS="$BUILD_DIR/classes/com/thorium/launchbridge/StopButtonService.class"
# Recompile patched copies so the standalone controller source remains valid.
PATCHED_SRC="$BUILD_DIR/work/patched-src/com/thorium/launchbridge"
mkdir -p "$PATCHED_SRC"
cp "$LAUNCH_SRC" "$STOP_SRC" "$PATCHED_SRC/"
perl -0pi -e 's/com\.thorium\.launchbridge\.roms/com.thorium.preview.launchbridge.roms/g' \
    "$PATCHED_SRC/LaunchActivity.java"
perl -0pi -e 's/private static final String PEGASUS_PACKAGE = "org\.pegasus_frontend\.android";/private static final String PEGASUS_PACKAGE = "com.thorium.preview";/' \
    "$PATCHED_SRC/StopButtonService.java"
rm -f "$LAUNCH_CLASS" "$STOP_CLASS" "$BUILD_DIR/classes/com/thorium/launchbridge/LaunchActivity"\$*.class \
    "$BUILD_DIR/classes/com/thorium/launchbridge/StopButtonService"\$*.class
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 -classpath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" "$PATCHED_SRC/LaunchActivity.java" "$PATCHED_SRC/StopButtonService.java"

CLASS_INPUTS=$(find "$BUILD_DIR/classes" -name '*.class' -print)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --classpath "$BUILD_DIR/stub-classes" \
    --output "$BUILD_DIR/dex" $CLASS_INPUTS

UNSIGNED="$BUILD_DIR/lucent-unified-unsigned.apk"
"$APKTOOL" b "$DECODED" -o "$UNSIGNED" >/dev/null
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/work/classes2.dex"
(cd "$BUILD_DIR/work" && "$BUILD_TOOLS/aapt" add "$UNSIGNED" classes2.dex >/dev/null)

ALIGNED="$BUILD_DIR/lucent-unified-aligned.apk"
OUTPUT="$BUILD_DIR/lucent-unified-$VERSION_NAME.apk"
"$BUILD_TOOLS/zipalign" -f 4 "$UNSIGNED" "$ALIGNED"
KEYSTORE=${LUCENT_KEYSTORE:-$ROOT_DIR/android-companion/debug.keystore}
STORE_PASS=${LUCENT_STORE_PASS:-android}
KEY_PASS=${LUCENT_KEY_PASS:-$STORE_PASS}
KEY_ALIAS=${LUCENT_KEY_ALIAS:-androiddebugkey}
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
    --out "$OUTPUT" "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT" >/dev/null
printf '%s\n' "$OUTPUT"
