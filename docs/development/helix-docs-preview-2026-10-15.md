# Helix Docs development preview — 2026-10-15

## Scope

This is a local, debug-signed preview of the first Helix help slice. The built-in `helix-docs` Skill exposes an English offline guide through a content-hashed manifest and bounded, on-demand resource reads. It includes eight sections and does not add a static help screen.

The guide revision is `c9d397849ac0dcca005eb62c9dec2731e262eb82b77b80cdad4cd5c467031b25`. Its manifest documents app version `0.0.5`, version code `1`, and the `consumer` and `developer` distributions. The revision identifies guide content; it does not bind the APK to a source commit, dirty-tree state, selected flavor, or signing identity.

## Local preview artifacts

These APKs were built from the current working tree on 2026-10-15. They are debug builds for review, not release candidates.

| Variant | Application ID | APK | SHA-256 |
| --- | --- | --- | --- |
| consumerDebug | `com.helix.agent` | `app/build/outputs/apk/consumer/debug/app-consumer-debug.apk` | `c0f486826be6e9dbb992901640ddaf9c0059fc0e149982cc335197e32b1aeb25` |
| developerDebug | `com.helix.agent.developer` | `app/build/outputs/apk/developer/debug/app-developer-debug.apk` | `7e284ac9f77bbfe96eb9b5cb0ed89541ba03ef74af0ba6683a9b899ad41c4988` |

Instrumentation APK hashes for this verification build:

| Variant | APK | SHA-256 |
| --- | --- | --- |
| consumer | `app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk` | `777d3c67e24faa55244253811304f1244758f213b7b82749ba99146317818eca` |
| developer | `app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk` | `f376d7acc35bdae8cfaba2aa8a0cfd69d80eb78cc968acd9dd3b7c91051f8244` |

## Verification

- `:app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest` — passed.
- `HelixDocsDeviceTest` on the newly created isolated API 36 AVD — 1/1 passed. It checked built-in discovery, manifest/version/distributions, chapter hash, and fail-closed unknown-resource behavior.
- `HelixDocsLiveModelDeviceTest` against local `Qwen3.8-27B` via ADB reverse on port 30008 — 1/1 passed. The model read the injected `helix-docs` catalog entry, manifest, index and channel section; cited version `0.0.5`, distributions, section ID/title and full revision; and said OnePlus data-preserving over-install behavior is not documented. It made one bounded failed attempt without the `references/` prefix before reading the canonical section path.
- Evidence is retained under `build/helix-docs-device-2026-10-15/run-4` and `run-8-live`. Earlier fixture failures are separately retained in `run-1` through `run-3` and `run-5-live` through `run-7-live`; they are not passing evidence.

The live model answer correctly distinguished the general warning about development database upgrades from the undocumented OnePlus-specific upgrade outcome. This does not establish behavior on a physical OnePlus device.

## Signing and channel decisions still required

- Decide the final consumer/developer channel mapping, package identity, and whether their application IDs remain separate.
- Select and protect production signing identities. Confirm certificate ownership, key custody/rotation, and the upgrade lineage each channel must preserve. Debug signing is not a production identity.
- Establish same-ID upgrade behavior against preserved test data for each supported channel and API/OEM combination. Capture a restorable backup first; verify database migration, files, configuration and conversations independently. Stop on signature conflict or uncertain data migration; do not use uninstall as a workaround.
- Build release artifacts from a recorded clean commit with the selected flavor and production signing configuration. Record APK SHA-256, certificate fingerprint, version name/code, application ID, source commit and build inputs together.
- Review the final release notes and install/rollback guidance before any device rollout.

No version values, signing identity, release status, or installed device were changed by this preview work.
