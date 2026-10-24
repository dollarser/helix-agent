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

## Collect original sources (current source)

Choose **Collect in Helix** (or **Collect in Helix Advanced**) from another app’s **Android system share** sheet to save text, links, images or files in the local source library. Collected material is not automatically confirmed knowledge. Knowledge retains originals, shows extraction limits, and supports search and selected references. WeChat’s internal forwarding menu may not expose system sharing; copy text/link or save a file and share it instead.

Knowledge notes are independent: write directly, distill one source, or select several sources to synthesize. Optional model generation requires choosing a provider/model and confirming the destination and selected content. Its output remains an unreviewed suggestion until you load it into the editor, edit and save a reviewed note. Citation snapshots remain fixed when sources are re-extracted. You can also ask in chat using selected search results without creating a note. Original sources remain in the library; no inbox-clearing workflow is required.

Public pages are fetched without login. Unavailable, title-only, login-dependent or unsupported material is marked incomplete; images are retained without claiming OCR. The library is separate from personal memory and is never automatically injected wholesale into model context. This feature is not yet published; see [HXA-308](docs/development/tasks/HXA-308.md).

## Your first task

### Local notes and reminders (not released yet)

Open **Work → Notes & reminders** for an untimed note, or confirm a date, fixed time zone and once/daily/weekly/monthly recurrence. Alerts post a system notification without calling a model; tapping opens the entry. Editing replaces the old alarm; completing or cancelling stops the entire series. Without exact-alarm access, alerts are approximate. Disabled notifications/channels cannot display them; battery restrictions may delay them and force-stop requires reopening the app. Occurrences over 15 minutes late are recorded as missed without replay. See [HXA-309](docs/development/tasks/HXA-309.md).

1. Download an APK from [Releases](https://github.com/dollarser/helix-agent/releases) and follow Android's installation prompts. Use the notes and APK checksums in that version's Release body.
2. The current app fetches **Helix built-in models** in the conversation model picker after Helix account login, without an API key. Directory membership does not verify inference connectivity. This project cloud service has shared limits and receives messages, context, tool results and selected images. You can add your own API or on-device model under **Models**; existing choices are preserved. Built-in text, image and bounded file workflows have been validated; current shared-service availability still depends on actual requests. See [HXA-307](docs/development/tasks/HXA-307.md) for the validation scope.
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

The **[v0.0.7 development preview](https://github.com/dollarser/helix-agent/releases/tag/v0.0.7)** provides ARM64 Standard and Advanced, R8 optimized and non-debuggable, with versionCode 2 and the existing development certificate. The Release body is authoritative for publication status, exact commit, checksums and CI.

Install your edition over the existing app without uninstalling or clearing data. Incompatible preview databases require explicit on-device confirmation; cancellation preserves data. Built-in models require account login. Custom APIs, supported subscriptions and downloaded local models remain available without a Helix account. Offline ASR/TTS weights are downloaded on demand.

See the [v0.0.7 notes](docs/product/releases/v0.0.7.en.md). Only APKs and tagged source archives are distributed on the version page; exact third-party sources and notices remain in the runtime asset Release. Emulator checks do not imply physical-device, store-signing or long-term migration acceptance.

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

### Local reply reading

Completed assistant replies offer an explicit read/stop action. First use requires an explicit download of about 57.8 MiB of pinned Melo INT8 voice resources. Text stays on the device; downloading does not start playback. Leaving the conversation, entering voice input, backgrounding or losing audio focus stops reading. Source builds prepare `python3 scripts/build-asr-assets.py` followed by `python3 scripts/build-tts-assets.py` before Gradle.
