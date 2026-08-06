# Lucent unified Android package

This build combines the official 64-bit Pegasus Android runtime, the Lucent
theme, preview player, library importer, media enrichment, updater, ROM launch
bridges, and Thor Stop-button accessibility service into one APK.

The application id intentionally remains `com.thorium.preview`, so it upgrades
the earlier Lucent companion in place and retains its updater state. The
embedded Pegasus Java/JNI class names stay unchanged for binary compatibility.

The base runtime is Pegasus Frontend `alpha16-105-g6b322063`, built from
[`mmatyas/pegasus-frontend`](https://github.com/mmatyas/pegasus-frontend) and
licensed under GPLv3. Lucent is a modified distribution and must be distributed
with corresponding source and the GPLv3 license. Product logos and trademarks
remain the property of their respective owners.

Run `./build.sh` to create `build/lucent-unified-3.0.36.apk`.
