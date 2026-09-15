# Third-party notice policy

Helix source code is licensed under Apache License 2.0. Third-party libraries,
tools, runtime assets, models, and command-line artifacts retain their own
licenses and are not relicensed by the Helix project.

## JGit local TLS correction

The app uses Eclipse JGit `6.10.0.202406032230-r` under its upstream EDL-1.0
(BSD-3-Clause) license. Its original license and notices remain in the dependency.
Helix replaces only `NoCheckX509TrustManager` with an original implementation that
rejects both certificate checks, disabling JGit's TrustAll path. This is a locally
modified artifact, not an unmodified upstream release. The original jar signature
files are removed from the modified artifact; Maven input verification is retained.
Source, checksum, modification and build details: [local correction](config/jgit/README.md).

## Required process

1. Every shipped third-party artifact must be pinned in the version catalog,
   runtime lock, or another reviewed lock file.
2. Before a release, resolved artifacts must be reconciled with Gradle
   dependency verification metadata, the APK contents, and this notice set.
3. Copyright, attribution, source-offer, modification, and redistribution
   obligations must be preserved exactly as required by the upstream license.
4. Copyleft or source-available code is not copied into Helix without an
   explicit architecture and license decision.
5. PRoot, RootFS packages, QuickJS/Zipline native libraries, official CLI
   artifacts, and model files require a separate source-and-license manifest
   before they can be bundled.

This file is a notice policy and a partial attribution inventory, not a complete
release SBOM. Current debug APKs include Maven dependencies and QuickJS/Zipline
native libraries; optional PRoot/RootFS/CLI assets have separate lock and license
gates. Before M12 distribution, the exact resolved release artifacts (including
transitive dependencies and native assets) need a generated SBOM, upstream license
texts and required notices reconciled against the shipped APKs. A Compose BOM is
a dependency constraint, not a shipped binary. Passing dependency hash checks or
building an unminified release APK does not close this distribution gate.

## topjohnwu/libsu 6.0.0

- Components: `com.github.topjohnwu.libsu:core:6.0.0` and
  `com.github.topjohnwu.libsu:service:6.0.0`.
- Source: <https://github.com/topjohnwu/libsu/tree/6.0.0>, lightweight tag at
  commit `8c3e80ffe466b89ff875471e542d77e6a1b480c5`.
- Copyright: John Wu and libsu contributors.
- License: Apache License 2.0; the upstream tag contains `LICENSE` and no
  separate `NOTICE` file.
- Distribution: unmodified JitPack AARs, restricted to the exact
  `com.github.topjohnwu.libsu` group and pinned by Gradle dependency
  verification. The `service` AAR includes `assets/main.jar`, which libsu uses
  to bootstrap its RootService process; it is part of the verified AAR rather
  than Helix-authored code.
- Verified AAR SHA-256: `core` =
  `a1ca5a8adb9ab11c42b71fc2d2a61b5a95cb4cdd06df0eb8c204c06813c2bb5b`;
  `service` =
  `528bbcc3f057e8b5ea6f69a2deea1e0ff536880602e99e98763d7b74cf1d0659`.

## AndroidX ExifInterface 1.4.2

- Component: `androidx.exifinterface:exifinterface:1.4.2` (Google Maven).
- Source and release: <https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2>.
- Copyright: The Android Open Source Project; Apache License 2.0.
- Unmodified library used only for local image EXIF orientation parsing; replaces
  the platform implementation flagged by Android Lint. Metadata is still stripped
  by bitmap re-encoding, under the existing ADR-0014 bounds.
