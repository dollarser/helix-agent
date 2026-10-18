# Helix

[简体中文](README.md) | **English**

**An open-source Android AI agent that turns requests into actions on your phone.**

Helix executes tasks on Android: interacting with supported apps, browsing the web, handling files, and running tools. Choose your model and access scope, follow execution, and keep results in conversations and files you can use again.

Looking for an **open-source alternative to the Doubao phone-assistant experience**? Helix explores that direction through its Mobile Use plugin, connecting phone interaction, web browsing, and file workflows to a model you choose. Helix is an independent Apache-2.0 project with no official affiliation with Doubao or its developer. It does not require OEM preinstallation and does not claim equivalent system privileges or feature parity.

[Download preview](https://github.com/dollarser/helix-agent/releases) · [Get started](#your-first-task) · [Documentation](docs/README.md) · [Issues](https://github.com/dollarser/helix-agent/issues) · [Contribute](CONTRIBUTING.md)

> **Development preview. Android 10+ required; old data is not guaranteed to survive updates.** Published APKs currently use debug signing. An incompatible database upgrade asks you to confirm before permanently clearing the database's conversations, tasks, configuration, and authorization records. Cancelling exits and preserves old data; files outside the database are retained. Save important content separately; diagnostic exports are not restorable app backups. Features on `main` may not be in published APKs.

## What you can do

| Capability | How it helps |
| --- | --- |
| **Phone interaction · Mobile Use** | Observe screens, find elements, tap, scroll, and type using available Accessibility, Shizuku, or Root capabilities |
| **Files and results** | Use an independent file manager; read, organize, create, and edit authorized files, then preview, locate, or share results |
| **Web and tools** | Browse pages, collect information, and call tools; Developer builds also include an advanced terminal and execution environment |
| **Conversations and projects** | Organize conversations, files, instructions, and memory around projects; queue messages, adjust tasks, or stop execution |
| **Model choice** | Connect APIs, compatible self-hosted services, or on-device models; Developer adds experimental third-party subscription integrations |
| **Plugins** | Organize capabilities through plugins, skills, and connectors, including MCP; select plugins per conversation |

Task quality depends on the model, device, permissions, and target app. Helix is not an OEM system image and does not promise compatibility with every app, website, or file format. See [current status](docs/development/status.md) for implementation and verification boundaries.

### Turn a conversation into useful work

- **Operate your phone**: find content, enter information, and complete steps in apps you authorize while following progress and adding instructions.
- **Turn web research into a deliverable**: browse public pages, compare sources, and organize findings into a file you can preview or share.
- **Continue with context**: conversations, project directories, and optional memory preserve task context. Connect your own API or on-device model and choose plugins for each task.

Mobile Use is an optional plugin. Browser, file, and tool access retain their own authorization boundaries. Check the requested scope and actual results for account, payment, or other consequential operations.

## Your first task

1. Download an APK from [Releases](https://github.com/dollarser/helix-agent/releases) and follow Android's installation prompts. Use the release notes and checksum files for that version.
2. Fresh installs built from current source select **Helix built-in Qwen3.8-27B** without a key. This project cloud service has shared limits and receives messages, context, tool results and selected images. You can add your own API or on-device model under **Models**; existing choices are preserved. Published v0.0.6 does not include this integration and still requires a provider address, protocol, key (if required) and model name. See [HXA-307](docs/development/tasks/HXA-307.md) for live integration status.
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

Configure **Mobile Use** in Plugins, connect at least one usable screen backend, then select it in the conversation. If setup or connection is missing, clicking the checkbox opens the relevant settings; select it again after setup. Helix manages system authorization and connections for Accessibility, Shizuku, and Root; the plugin uses capabilities granted to the host. All permissions are not required at once, while each action still needs its specific capabilities.

Supported operations prefer available **Root → Shizuku → Accessibility** backends. Backend coverage differs, and elevated permissions do not guarantee access to every secure window. Verify uncertain outcomes before repeating an action that may already have taken effect.

## Choose a build

| Edition | Capabilities |
| --- | --- |
| **Standard / consumer** | The complete base product: conversations, API/on-device models, and channel-supported tools; excludes third-party subscription runtimes and the PRoot terminal |
| **Advanced / developer** | Adds advanced capabilities allowed by the platform/channel, including subscription adapters, a terminal, and a bundled Linux environment; intended for development and advanced use |

Build editions are separate from conversation permission modes. Subscription adapters require eligible accounts and service-specific testing; they do not imply official provider endorsement. Plans using an API address and key belong under API configuration.

The **[v0.0.6 development preview](https://github.com/dollarser/helix-agent/releases/tag/v0.0.6)** targets ARM64 with R8-optimized, non-debuggable APKs. It retains internal `versionCode` `1` and the existing development signing certificate:

| Edition | Package |
| --- | --- |
| Standard | `helix-v0.0.6-consumer-arm64-v8a.apk` |
| Advanced | `helix-v0.0.6-developer-arm64-v8a.apk` |

This version focuses on plugin and conversation UX, file-path presentation, task results and recovery reliability, and on-demand Linux preparation in Advanced. The first authorized Linux task or manually opened terminal prepares bundled resources automatically, without requiring a verification step in Settings. Execution remains subject to the current mode and authorization scope.

This version also fixes browser page layout and snapshots, notification gestures cancelled by valid window transitions, empty progress disclosures and automatic conversation titles, while retaining the compact inline composer.

See the [v0.0.6 notes and validation boundaries](docs/product/releases/v0.0.6.en.md). Download only assets available on the Release page and check `BUILDINFO.json` and `SHA256SUMS`. Validation primarily uses the API36 ARM64 emulator, without physical-phone/OEM acceptance. Browser resource testing lasted 15 minutes; the Douyin task passed after supplying download facts and a menu entry, and the model can still search or act redundantly. Preserve existing data when signatures conflict, the installed versionCode exceeds 1, or the database is incompatible; do not immediately uninstall or clear data.

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
- [Development Agent Skills](docs/development/implementation-guide.md#开发-agent-skills): repository acceptance and contract-review workflows, separate from in-app plugin skills.
- [Report a bug or suggest an improvement](https://github.com/dollarser/helix-agent/issues): include version, build edition, Android/device details, model, and reproduction steps. Remove keys, credentials, and private content.
- Created by [dollarser](https://github.com/dollarser), with community contributions welcome.

## License

Helix's own source code is licensed under **[Apache License 2.0](LICENSE)**. Third-party libraries, runtime assets, command-line tools, and model weights retain their respective licenses; using them in Helix does not relicense them under Apache-2.0.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attribution and release review requirements. It is currently a partial inventory and review policy, not a complete release SBOM or confirmation that all redistribution obligations are satisfied.
