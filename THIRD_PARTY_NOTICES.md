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
