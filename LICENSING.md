# Lucent licensing

Lucent is published in two forms with deliberately separate license scopes.

## Lucent application

The complete Android application combines a modified Pegasus Frontend runtime
with Lucent's Android integration, importer, media services, direct-launch
bridges, updater, and default theme. The combined application and all source
outside `theme/` are distributed under the **GNU General Public License,
version 3.0 only** (`GPL-3.0-only`). The complete license is in [`LICENSE`](LICENSE).

Pegasus Frontend is copyright Mátyás M. and its contributors. Lucent is not an
official Pegasus release and is not endorsed by the Pegasus project.

## Standalone Lucent theme

The contents of `theme/` may also be downloaded and used independently with an
existing Pegasus installation. That standalone theme is distributed under the
MIT License found in [`theme/LICENSE`](theme/LICENSE).

Bundling the theme into the Lucent application does not change the GPL terms
that govern the combined application as a whole.

## Corresponding source

The scripts and Lucent-authored source required to reproduce the distributed
APK are in this repository. The exact unmodified Pegasus base used by the build
is commit [`6b322063`](https://github.com/mmatyas/pegasus-frontend/tree/6b322063).
The reproducible transformation and packaging steps are in
[`unified-android/build.sh`](unified-android/build.sh). See
[`SOURCE_OFFER.md`](SOURCE_OFFER.md) for a complete source map.

Product names, console logos, emulator names, and other third-party marks and
artwork remain the property of their respective owners. Their use is for
identification and does not imply sponsorship or endorsement.
