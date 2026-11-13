# Changelog

Notable changes are curated for users; development previews do not promise stable data or API compatibility. Exact build identity, downloadable APKs, checksums and verification results are recorded in each GitHub Release.

## 0.0.11 — Development preview preparation, unpublished

### Added

- Explicit cross-session history, management, creation and task-delivery grants, with idempotent creation, Queue and active-Turn Steer.
- Bounded public web fetching, browser search/form actions, opt-in search providers and binary MCP Resources delivered as host-managed artifacts.
- Plugin-owned skills, atomic composer skill tokens and slash discovery.
- Advanced FFmpeg media workflows through the existing PRoot/Job runtime.
- Computer ADB helper support for non-debuggable Advanced builds on Android 12+, using app-issued, one-use, process-bound handoff tickets. Standard has no helper entry; wireless pairing and cross-process task recovery are not added.

### Changed and fixed

- Single-page API settings, unified save/cancel, separate connection/model checks and model pickers grouped by service.
- Context compaction, bounded tool discovery, cache accounting, Goal coordination and durable tool/recovery boundaries.
- Real SAF search, revocation, directory handoff and refresh/layout handling.
- MediaCodec encoder initialization avoids the undefined encoder-flush preflight. Device support still depends on codec, dimensions and pixel format.
- FFmpeg's packaged notice now describes the integrated runtime and bounded Android checks instead of its earlier standalone preparation stage; the notice update preserves all native bytes and original licenses.

### Upgrade and validation notes

VersionName is 0.0.11; versionCode and Helix-owned internal versions remain 1. The database structure differs from v0.0.10. In-place installation and historical data retention are not guaranteed; incompatible database reconstruction requires independent confirmation. Published v0.0.10 tags and artifacts remain unchanged.

Full local Debug and Release/R8 host gates passed. Bounded ONEPLUS_A6013/API34 checks include 30 Debug checks and 8 isolated, test-signed non-debuggable R8 entry checks. The final media subset was rerun on that phone and an API36 emulator; these repeated checks are not additional unique product coverage. Ordinary Release APKs remain unsigned. No final release commit, remote CI, tag, upload or publication is claimed.

[中文准备说明](docs/product/releases/v0.0.11.md) · [English preparation notes](docs/product/releases/v0.0.11.en.md)

## [0.0.10](https://github.com/dollarser/helix-agent/releases/tag/v0.0.10) — Development preview

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

## Earlier previews

- **[0.0.9](https://github.com/dollarser/helix-agent/releases/tag/v0.0.9)** — [中文](docs/product/releases/v0.0.9.md) / [English](docs/product/releases/v0.0.9.en.md)
- **[0.0.8](https://github.com/dollarser/helix-agent/releases/tag/v0.0.8)** — [中文](docs/product/releases/v0.0.8.md) / [English](docs/product/releases/v0.0.8.en.md)
- **[0.0.7](https://github.com/dollarser/helix-agent/releases/tag/v0.0.7)** — [中文](docs/product/releases/v0.0.7.md) / [English](docs/product/releases/v0.0.7.en.md)
- **[0.0.6](https://github.com/dollarser/helix-agent/releases/tag/v0.0.6)** — [中文](docs/product/releases/v0.0.6.md) / [English](docs/product/releases/v0.0.6.en.md)
