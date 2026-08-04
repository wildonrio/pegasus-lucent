#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK_DIR=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/tyleryoung/Code/cemu/Cemu-0.5/android-sdk}}
BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION:-36.0.0}
ANDROID_PLATFORM=${ANDROID_PLATFORM:-android-36}
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_DIR/platforms/$ANDROID_PLATFORM/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/resources.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    -A "$PROJECT_DIR/assets" \
    -o "$BUILD_DIR/unsigned.apk" \
    "$BUILD_DIR/resources.zip"

"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    $(find "$PROJECT_DIR/src" -name '*.java' -print)

"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" \
    $(find "$BUILD_DIR/classes" -name '*.class' -print)
(cd "$BUILD_DIR/dex" && "$BUILD_TOOLS/aapt" add "$BUILD_DIR/unsigned.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

KEYSTORE=${LUCENT_KEYSTORE:-$PROJECT_DIR/debug.keystore}
STORE_PASS=${LUCENT_STORE_PASS:-android}
KEY_PASS=${LUCENT_KEY_PASS:-$STORE_PASS}
KEY_ALIAS=${LUCENT_KEY_ALIAS:-androiddebugkey}
if [ ! -f "$KEYSTORE" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS" -alias "$KEY_ALIAS" -dname 'CN=Pegasus Lucent,O=WildOnRio,C=US' \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" --out "$BUILD_DIR/pegasus-lucent.apk" "$BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$BUILD_DIR/pegasus-lucent.apk"
printf '%s\n' "$BUILD_DIR/pegasus-lucent.apk"
