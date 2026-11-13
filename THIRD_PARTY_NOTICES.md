# Third-party notice policy

Helix source code is licensed under Apache License 2.0. Third-party libraries,
tools, runtime assets, models, and command-line artifacts retain their own
licenses and are not relicensed by the Helix project.

## Web document parsing

jsoup 1.24.1 is used only to parse already-fetched HTML, under its MIT license.
The unmodified upstream notice is bundled as `assets/licenses/jsoup-MIT.txt`.
Its Maven inputs are pinned in the version catalog, dependency locks and
verification metadata. The Netty BOM referenced by its POM is resolution
metadata for upstream tests, not a new shipped Netty runtime dependency.

## Offline formula previews

KaTeX 0.19.1's unmodified browser script, stylesheet and fonts are bundled for
user-opened, local formula previews under its MIT license. The original license
is packaged in `assets/licenses/KaTeX-MIT.txt`. The official npm archive, registry
integrity and per-file checksums are pinned in `config/math/katex.lock.json`;
`scripts/verify-formula-assets.py` checks them offline. Helix does not load CDN
scripts, fonts or formula text over the network. KaTeX is not a general browser
or an Agent tool execution surface.

## Local speech

ASR and optional Melo reply reading use the pinned sherpa-onnx/ONNX Runtime
inputs in `config/asr/dependencies.lock.json`. The custom JNI build excludes
Piper/eSpeak and retains the shared dependency notices under APK assets
`asr-licenses/` and `tts-licenses/`. Optional Melo INT8 weights, lexicon and text
rules are MIT licensed; their source revision, download URLs and integrity are
recorded in `config/tts/model.lock.json`. These resources download only after
the user chooses to install the local voice. Build modifications and scope are
documented in `scripts/build-tts-assets.py` and ADR-PLATFORM-004.

## JGit local TLS correction

The app uses Eclipse JGit `6.10.0.202406032230-r` under its upstream EDL-1.0
(BSD-3-Clause) license. Its original license and notices remain in the dependency.
Helix replaces only `NoCheckX509TrustManager` with an original implementation that
rejects both certificate checks, disabling JGit's TrustAll path. This is a locally
modified artifact, not an unmodified upstream release. The original jar signature
files are removed from the modified artifact; Maven input verification is retained.
Source, checksum, modification and build details: [local correction](config/jgit/README.md).

## Required process

The locked Termux PRoot/talloc/libandroid-shmem packages, corresponding sources,
build scripts and copyright/license notices are available together in the
[runtime asset release](https://github.com/dollarser/helix-agent/releases/tag/runtime-assets-20260917).
See the bundled [source and notice index](runtime/proot-app/src/main/assets/runtime/licenses/TERMUX-README.md)
for exact scope, download links and verification boundaries. This component
archive does not replace the full application distribution checks below.

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

This file is a notice policy and a partial attribution inventory. The existing
[runtime asset release](https://github.com/dollarser/helix-agent/releases/tag/runtime-assets-20260917)
retains the v0.0.6 resolved dependency inventory and original upstream notices in
[`helix-v0.0.6-third-party-notices.zip`](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/helix-v0.0.6-third-party-notices.zip).
The application Release body records the APK source commit, CI and checksums.
A resolved dependency is a build input, not proof that all of its classes survive
R8. A Compose BOM is a constraint, not a shipped binary.
Alpine package versions/origins/commits, aports recipes and checksum-verified
source inputs are distributed in `alpine-corresponding-source.tar.gz` and
`alpine-source-manifest.json` in that runtime asset Release, together with
`media-corresponding-source.tar.gz`. The application Release directly links these
exact materials. Termux materials remain in the same fixed resource release.
Native/runtime locks and original
upstream licenses remain authoritative; Helix's Apache-2.0 license does not
replace them. Passing hash checks alone is not a distribution-license review.

For v0.0.11, the patched FFmpeg 9.0.2 Ready-AV1 build has distinct
[corresponding-source materials](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/helix-ffmpeg-9.0.2-ready-av1-20261113-notice-source.tar.gz).
The published archive contains ten pinned upstream sources, the MediaCodec
patch, build/relinking instructions and original licenses. It was read back
and verified without replacing assets needed by older releases. Its embedded
application commit records the original candidate, not every later app build.
Reuse for a later application requires matching the recipe, patch, build
inputs, NOTICE and all eight media native files, and identifying the complete
application source separately. The old `media-corresponding-source.tar.gz`
must not be substituted for this patched binary. The maintained recipe and
patch are in [the FFmpeg preparation kit](scripts/experiments/ffmpeg/README.md).
See [v0.0.11 preparation](docs/product/releases/v0.0.11.en.md) for the current
release and validation boundary.

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
  by bitmap re-encoding, under the existing ADR-AGENT-003 bounds.

## Mermaid Tiny 12.2.0

- Official fixed `@mermaid-js/tiny` browser distribution, MIT; unmodified script.
- [Source/release](https://github.com/mermaid-js/mermaid/tree/mermaid%4012.2.0/packages/tiny).
- Root license: `ui/shared/src/main/assets/licenses/Mermaid-MIT.txt`; conservative runtime dependency notices: `ui/shared/src/main/assets/licenses/Mermaid-dependencies.txt`. DOMPurify is used under its Apache-2.0 alternative, not MPL-2.0.
- Tiny excludes ELK and KaTeX rendering; no diagram-triggered downloads, callbacks or external navigation.
- Reviewed archive integrity, asset/notice hashes and dependency provenance: `config/mermaid/mermaid.lock.json`.

## DOMPurify 3.4.12 (standalone SVG input)

- Official unmodified npm `dompurify` browser distribution; used under its Apache-2.0 alternative.
- Source/license: <https://github.com/cure53/DOMPurify/tree/3.4.12>.
- Original license: `ui/shared/src/main/assets/licenses/DOMPurify-LICENSE.txt`; pinned distribution integrity: `config/svg/dompurify.lock.json`.
- Used with independent SVG/CSS/resource limits and no network or native bridge; not an arbitrary HTML renderer.

## core-js-bundle 3.51.0

- Source: https://github.com/zloirock/core-js ; official npm `core-js-bundle` fixed archive.
- MIT license: `ui/shared/src/main/assets/licenses/core-js-MIT.txt`. Bundled only in the isolated offline Mermaid script to supply standard APIs on the tested older WebView.
- Mermaid syntax is transformed by build-time esbuild 0.28.2 (MIT); its verified tool inputs and exact arguments are recorded in `config/mermaid/mermaid.lock.json`. The build tool is not shipped in the APK.

## Silero VAD (v5.0, MIT)

Continuous dictation uses the official `silero_vad.onnx` v5.0 model with the
already pinned sherpa-onnx/ONNX Runtime. Its MIT license and fixed-source lock
are bundled in `assets/voice-vad/`; source and integrity metadata live in
`config/vad/model.lock.json`. `scripts/prepare-vad-assets.py` prepares and verifies
this small endpoint detector. It is not an ASR model, transcription service or
permission to capture audio. The recognition models retain their on-demand policy.
