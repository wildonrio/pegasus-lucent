# Corresponding source for Lucent

This repository is the corresponding source for the Lucent Android application.
It is provided at no charge alongside every downloadable APK.

## Source map

- Pegasus Frontend base: version `alpha16-105-g6b322063`, exact source commit
  [`6b322063`](https://github.com/mmatyas/pegasus-frontend/tree/6b322063)
- Expected upstream Android APK: `pegasus-fe_alpha16-105-g6b322063_android64.apk`
- Expected upstream APK SHA-256:
  `e595be198bfd21c1855eaf563d5af0deae9c9601e6efb195ed299f2065287c67`
- Lucent Android services: `android-companion/`
- Lucent launch and controller integration: `android-launch-bridge/`
- Lucent theme: `theme/`
- Complete transformation and build instructions: `unified-android/build.sh`

`unified-android/build.sh` downloads and verifies the exact upstream binary,
applies the package, manifest, and bytecode transformations recorded in the
script, compiles the Lucent sources, embeds the theme and license notices, and
produces the signed APK. A distributor may provide a different signing key via
the documented environment variables without altering the program source.

The upstream source and this repository are both hosted on GitHub and can be
downloaded without registration. Together they contain the source and scripts
needed to build, install, run, and modify the version of Lucent distributed in
the matching release.
