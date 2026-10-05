# Helix

[简体中文](README.md) | **English**

**An open-source Android AI agent that turns requests into actions on your phone.**

Helix executes tasks on Android: interacting with supported apps, browsing the web, handling files, and running tools. Choose your model and access scope, follow execution, and keep results in conversations and files you can use again.

[Download preview](https://github.com/dollarser/helix-agent/releases) · [Get started](#your-first-task) · [Documentation](docs/README.md) · [Issues](https://github.com/dollarser/helix-agent/issues) · [Contribute](CONTRIBUTING.md)

> **Development preview. Android 10+ required.** Published APKs currently use debug signing. During development, an incompatible database upgrade clears conversations, configuration, and database records while retaining files outside the database. Export important content and back up files before upgrading. Features on `main` may not be in published APKs.

## What you can do

| Capability | How it helps |
| --- | --- |
| **Phone interaction · Mobile Use** | Observe screens, find elements, tap, scroll, and type using available Accessibility, Shizuku, or Root capabilities |
| **Files and results** | Use an independent file manager; read, organize, create, and edit authorized files, then preview, locate, or share results |
| **Web and tools** | Browse pages, collect information, and call tools; Developer builds also include an advanced terminal and execution environment |
| **Conversations and projects** | Organize conversations, files, instructions, and memory around projects; queue messages, adjust tasks, or stop execution |
| **Model choice** | Connect APIs, compatible self-hosted services, or on-device models; Developer adds experimental third-party subscription integrations |
| **Extensions** | Organize capabilities through plugins, skills, and connectors, including MCP; select extensions per conversation |

Task quality depends on the model, device, permissions, and target app. Helix is not an OEM system image and does not promise compatibility with every app, website, or file format. See [current status](docs/development/status.md) for implementation and verification boundaries.

## Your first task

1. Download an APK from [Releases](https://github.com/dollarser/helix-agent/releases) and follow Android's installation prompts. Use the release notes and checksum files for that version.
2. Under **Models**, add a provider with its API address, protocol, and API key. Choose **Add model**, then enter the server's model name or fetch and select an available model. A custom display name is optional. You can also install a supported on-device model.
3. Start a conversation, select a model, and try a small file task:

```text
Organize tomorrow's tasks: a meeting at 10 a.m., mailing a package in the afternoon,
and buying milk in the evening. Group them by morning, afternoon, and evening.
Save them as tomorrow.md. Do not invent times I did not give you.
Read the saved file back and check that it matches.
```

4. Inspect execution status and open the file to check the result. Then try organizing a CSV, summarizing web pages, or operating a test app.

Remote services may charge for model usage. On-device models require downloaded weights, storage, and RAM. Configure a model before starting model-driven tasks.

### Operating Android apps

Enable **Mobile Use** in Extensions, configure it, and select it in the conversation. Helix manages system authorization and connections for Accessibility, Shizuku, and Root; the plugin uses capabilities granted to the host. All permissions are not required at once: partial readiness is supported, while each action still needs its specific capabilities.

Supported operations prefer available **Root → Shizuku → Accessibility** backends. Backend coverage differs, and elevated permissions do not guarantee access to every secure window. Verify uncertain outcomes before repeating an action that may already have taken effect.

## Choose a build

| Edition | Capabilities |
| --- | --- |
| **Standard / consumer** | The complete base product: conversations, API/on-device models, and channel-supported tools; excludes third-party subscription runtimes and the PRoot terminal |
| **Advanced / developer** | Adds advanced capabilities allowed by the platform/channel, including subscription adapters and the PRoot terminal; intended for development and advanced use |

Build editions are separate from conversation permission modes. Subscription adapters require eligible accounts and service-specific testing; they do not imply official provider endorsement. Plans using an API address and key belong under API configuration.

The current version is the **[v0.0.5 development preview](https://github.com/dollarser/helix-agent/releases/tag/v0.0.5)** (versionCode 5). Choose `helix-v0.0.5-developer-debug.apk` for advanced capabilities, or `helix-v0.0.5-consumer-debug.apk` for the base channel. SHA-256 checksums and build information accompany the release. If Android reports a signing conflict, preserve data before uninstalling or clearing app data.

This version improves Mobile Use and host permission ownership, model setup and subscriptions, conversation input, and extension management. Native network clients now share hosts-based domain resolution. It also fixes Release dependency locks, Android 10 permission API guards, and APK boundary checks. The full host CI passed; this release adds no device or real-model acceptance. See the [release record](docs/evidence/development/v0.0.5-release-2026-10-06.md) for the exact scope.

## Privacy and control

- **Local execution is not necessarily offline.** Remote models may receive conversations and task-relevant file excerpts, tool results, or images.
- On-device models run inference locally, but downloads, browsing, and network tools may still access the internet. APKs do not include model weights.
- System permissions and tool authorization are managed separately. Grant the required scope and inspect execution status; a model saying “done” is not independent proof of success.
- Stopping a task does not undo external actions already completed. Start with test data or copies.

## Build from source

Prepare **JDK 17, the Android SDK, and Python 3**. Follow the [development setup](docs/development/environment.md) for SDK/NDK and dependency configuration; repository locks define the versions.

```sh
git clone https://github.com/dollarser/helix-agent.git
cd helix-agent
./gradlew :app:assembleConsumerDebug
# For the advanced build:
./gradlew :app:assembleDeveloperDebug
```

APKs are written to `app/build/outputs/apk/`. Run `./scripts/check-all.sh --source` for host source checks. A successful build is not device, live-model, or release acceptance. See the [contribution guide](CONTRIBUTING.md) for workflow and testing requirements.

## Documentation and community

- [Current status and limitations](docs/development/status.md) · [Roadmap index](docs/development/roadmap.md)
- [Architecture](docs/architecture/overview.md) · [Design decisions](docs/adr/README.md) · [Documentation index](docs/README.md)
- [Report a bug or suggest an improvement](https://github.com/dollarser/helix-agent/issues): include version, build edition, Android/device details, model, and reproduction steps. Remove keys, credentials, and private content.
- Created by [dollarser](https://github.com/dollarser), with community contributions welcome.

## License

Helix's own source code is licensed under **[Apache License 2.0](LICENSE)**. Third-party libraries, runtime assets, command-line tools, and model weights retain their respective licenses; using them in Helix does not relicense them under Apache-2.0.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attribution and release review requirements. It is currently a partial inventory and review policy, not a complete release SBOM or confirmation that all redistribution obligations are satisfied.
