# Third-party notice policy

Helix source code is licensed under Apache License 2.0. Third-party libraries,
tools, runtime assets, models, and command-line artifacts retain their own
licenses and are not relicensed by the Helix project.

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

M0 does not bundle third-party source or runtime assets. Maven dependencies in
the debug application remain governed by their published upstream licenses;
the release notice inventory will be generated and verified before M12.

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
