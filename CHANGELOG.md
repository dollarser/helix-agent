# Changelog

Notable changes are curated for users; development previews do not promise stable data or API compatibility. Exact build identity, downloadable APKs, checksums and verification results are recorded in each GitHub Release.

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

VersionCode stays 1 and the development signing certificate is unchanged. Room schema compatibility does not cover old expert payloads: save and remove v0.0.9 expert-library entries before upgrading, then recreate them. There is no automatic migration or clearing. Computer ADB helper remains Developer Debug-only, not a feature of the published Release APKs.

[中文版本说明](docs/product/releases/v0.0.10.md) · [English release notes](docs/product/releases/v0.0.10.en.md) · [Full source comparison](https://github.com/dollarser/helix-agent/compare/v0.0.9...v0.0.10)

## Earlier previews

- **[0.0.9](https://github.com/dollarser/helix-agent/releases/tag/v0.0.9)** — [中文](docs/product/releases/v0.0.9.md) / [English](docs/product/releases/v0.0.9.en.md)
- **[0.0.8](https://github.com/dollarser/helix-agent/releases/tag/v0.0.8)** — [中文](docs/product/releases/v0.0.8.md) / [English](docs/product/releases/v0.0.8.en.md)
- **[0.0.7](https://github.com/dollarser/helix-agent/releases/tag/v0.0.7)** — [中文](docs/product/releases/v0.0.7.md) / [English](docs/product/releases/v0.0.7.en.md)
- **[0.0.6](https://github.com/dollarser/helix-agent/releases/tag/v0.0.6)** — [中文](docs/product/releases/v0.0.6.md) / [English](docs/product/releases/v0.0.6.en.md)
