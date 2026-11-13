# Changelog

[简体中文](CHANGELOG.zh-CN.md) | **English**

Notable changes for users, newest first. Entries are curated rather than copied from commit logs, using the grouping approach of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Development previews do not promise stable data or API compatibility. Published dates below use UTC; an unreleased candidate has no release date.

## 0.0.11 (Unreleased development preview)

[Changes since v0.0.10](https://github.com/dollarser/helix-agent/compare/v0.0.10...main) · [Release notes](docs/product/releases/v0.0.11.en.md) · [中文说明](docs/product/releases/v0.0.11.md)

### Added

- Manual update checks in Settings → About Helix, using ModelScope metadata to select the newest semantic version for the installed package and device ABI. A separate action opens the browser download; there are no background checks, automatic installations or data clearing.
- Separate permissions for cross-session history, management, creation and task delivery, including queued work and redirecting an active turn.
- Bounded public web fetching, browser search/form actions and opt-in search providers.
- Binary MCP Resources delivered as managed file artifacts, plus plugin-owned skills, composer skill tokens and slash discovery.
- Advanced FFmpeg media workflows through the existing PRoot/Job runtime.
- Computer ADB helper support for non-debuggable Advanced builds on Android 12+, using app-issued, one-use, process-bound handoff tickets. Standard has no helper entry; wireless pairing and cross-process task recovery are not added.

### Changed

- API settings use one page with unified save/cancel, separate connection/model checks and model pickers grouped by service.
- Tool calls show compact summaries with grouped, copyable details. Business content and internal diagnostics are presented separately without rewriting stored tool, model or audit records.
- Context compaction, bounded tool discovery, cache accounting and Goal coordination preserve durable execution and recovery boundaries.
- Local release acceptance now requires actual conversations, successful tool-result backfill, history after process restart, cancellation, no replay and continuation. Optional UI refinements are checked separately.
- FFmpeg's packaged notice describes its integrated runtime and bounded Android checks. Native bytes and original licenses are preserved; exact corresponding-source materials are linked from the release notes.
- Chinese and English READMEs focus on core capabilities, real interface previews and GitHub/ModelScope downloads. Version changes move to bilingual changelogs with verified historical dates and explicit preview upgrade risks.

### Fixed

- Ordinary conversations failing before the model request because the production Room store rejected the bounded `tools.cost` diagnostic. Unknown diagnostic types and payload limits remain enforced.
- Real SAF search, revocation, directory handoff and refresh/layout handling.
- MediaCodec initialization using an encoder flush, whose behavior is only defined for decoders. Support still depends on the device codec, dimensions and pixel format.

### Upgrade and validation notes

VersionName is 0.0.11; versionCode and Helix-owned internal versions remain 1. The database structure differs from v0.0.10. In-place installation and historical data retention are not guaranteed; incompatible database reconstruction requires independent confirmation. Android may reject installation over a preview with a higher versionCode. Do not uninstall merely to bypass this: uninstalling removes private application data. Published v0.0.10 tags and artifacts remain unchanged.

Both channels of the combined candidate passed the real-Room Debug core, focused update/About checks, isolated non-debuggable R8 conversation checks and complete host gate. See the [release notes](docs/product/releases/v0.0.11.en.md#final-core-acceptance-and-historical-evidence) for exact methods, devices and limits. Earlier phone entry/media checks are identified separately. Signing, commit, CI and download identities belong to the actual release record; this entry does not announce publication or availability of the update manifest.

## [0.0.10](https://github.com/dollarser/helix-agent/releases/tag/v0.0.10) - 2026-11-06 (Development preview)

### Added

- Project-aware conversation drafts, scoped working directories, source retrieval and richer file/result previews.
- Root task-screen support, adjustable previews, portable local application backups and optional first-launch permission setup.
- Inline math, offline Mermaid/SVG previews, continuous dictation and reply playback improvements.

### Changed

- Unified plugin/skill/expert management, leaner conversation menus and explicit configuration-on-selection flows.
- More actionable Mobile Use token/frame recovery, retained background tasks and bounded diagnostic history.
- Fast staged checks and exact-push-snapshot local hooks complement the retained CI gates.

### Fixed

- Browser input/ownership restoration, model selection after account changes, SAF grants and complete bounded text reading.
- Interrupted backup cleanup and startup database-reinspection failures.

### Upgrade notes

VersionCode stays 1 and the development signing certificate is unchanged. Room schema compatibility does not cover old expert payloads: save expert instructions and important content before upgrading. Existing plain-text entries require an external one-time conversion of an offline database copy; v0.0.9 has no deletion UI. Alternatively, defer upgrading or explicitly accept loss of private app data through a clean install. There is no automatic migration or clearing. Computer ADB helper remains Developer Debug-only, not a feature of the published Release APKs.

[中文版本说明](docs/product/releases/v0.0.10.md) · [English release notes](docs/product/releases/v0.0.10.en.md) · [Full source comparison](https://github.com/dollarser/helix-agent/compare/v0.0.9...v0.0.10)

## Earlier preview notes

Historical notes are retained even when an old download is no longer listed. Dates are included only when a publication record establishes them; a build or commit timestamp is not substituted.

- **[0.0.9](https://github.com/dollarser/helix-agent/releases/tag/v0.0.9) - 2026-10-29:** [中文](docs/product/releases/v0.0.9.md) / [English](docs/product/releases/v0.0.9.en.md).
- **0.0.8:** [中文](docs/product/releases/v0.0.8.md) / [English](docs/product/releases/v0.0.8.en.md).
- **0.0.7 - 2026-10-24:** [中文](docs/product/releases/v0.0.7.md) / [English](docs/product/releases/v0.0.7.en.md) / [archived publication record](docs/evidence/development/v0.0.7-publication-2026-10-24.md).
- **0.0.6:** [中文](docs/product/releases/v0.0.6.md) / [English](docs/product/releases/v0.0.6.en.md). The notes distinguish the original preview from its later redistribution.
