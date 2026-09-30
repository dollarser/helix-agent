# Helix

[简体中文](README.md) | **English**

**Less copying. Less switching between apps. Let Helix handle the small tasks on your phone.**

Research still needs organizing. Expenses still need adding up. An AI-written note still needs copying into a file. Those little steps take time.

Helix is an AI assistant that can take action on Android. Tell it what you want to accomplish. With your permission, it can read files, organize information, browse the web, interact with supported app screens, and save results you can use later.

**You set the goal. Helix takes the steps—and shows you what actually happened.**

[Download v0.0.4](https://github.com/dollarser/helix-agent/releases/tag/v0.0.4) · [Report an issue](https://github.com/dollarser/helix-agent/issues) · [Documentation](docs/README.md)

> This is a development preview for testing. APKs use debug signing; production signing and data-upgrade compatibility are not finished. An incompatible database upgrade clears conversations, settings, and database records while retaining files outside the database.

## Turn everyday chores into a request

For file tasks, place your text or CSV files in the conversation's working directory and grant the access the task needs.

### Turn scattered meeting notes into next steps

> “Organize the meeting notes in this directory into an action list with owners and deadlines. Mark missing details as unconfirmed. Save a new file and keep the originals.”

Keep the result on your phone, ready to review or update later.

### Make sense of expenses

> “Group this CSV of travel expenses by category, calculate the total, and list the five largest entries. Save a separate summary. Flag missing amounts instead of guessing.”

Let the assistant handle reading, calculation, and saving, then check the result against your records.

### Keep a useful record of your research

> “Read these event pages and summarize dates, locations, and registration details. Include source links and save a checklist. Mark anything you cannot find.”

Start with sources you choose and reduce the back-and-forth between your browser and notes.

### Hand off repetitive phone actions

> “Set screen brightness to the minimum and check that it changed.”

Helix can operate supported app screens after explicit authorization. A targeted system-brightness task has been verified; other apps need individual compatibility testing.

These are starting points, not guarantees for every file format, website, or app. Try copies or non-sensitive data first.

## Useful results, visible progress

- **Keep what you create.** Open generated files and continue using the same working directory in later tasks.
- **Work through larger tasks.** Ask for a plan first, review progress, and stop or adjust the task along the way.
- **Control access.** File access and phone actions require appropriate permissions. Approval, refusal, and uncertain execution states remain visible.
- **Choose your model.** Connect a model service or download a supported local model in the app.

Results depend on the model, available tools, device, and permissions. A model saying “done” is not proof that an operation succeeded; Helix displays execution status separately.

## Download and install

Requires **Android 10 or later**.

| Package | Which should I choose? |
| --- | --- |
| [Developer APK](https://github.com/dollarser/helix-agent/releases/download/v0.0.4/helix-v0.0.4-developer-debug.apk) | **Recommended for trying Helix.** Starts in Standard mode and includes optional advanced capabilities such as the terminal. |
| [Consumer APK](https://github.com/dollarser/helix-agent/releases/download/v0.0.4/helix-v0.0.4-consumer-debug.apk) | A build without subscription runtimes or the PRoot terminal. |

Allow installation from your chosen source when Android asks. The [release page](https://github.com/dollarser/helix-agent/releases/tag/v0.0.4) includes release notes, source code, and SHA-256 checksums.

> **Before upgrading, export important conversations and content, and back up important files.** During development, an incompatible database upgrade clears conversations, configuration, and database records. Only files outside the database are retained.

If Android reports a signing conflict, preserve your data before taking action. Do not immediately uninstall or clear app data; development builds from different sources may not support installation over one another.

## Models and editions

The current Consumer source build supports **API/self-hosted services** and **on-device models**. Developer also includes third-party subscription-account integrations: Codex, Claude, Google Antigravity, GitHub Copilot, and Grok (X Premium). Build editions are separate from conversation permission modes. Availability and account eligibility need verification for each service; integration does not imply official authorization or complete compatibility.

Endpoint-and-API-key plans, such as Kimi Code and MiniMax Token Plan, belong under API connections in both editions. Use the key for your plan and region; Helix does not automatically switch billing accounts. Antigravity is experimental. Developer offers experimental sign-in with overridable or disableable public client parameters, not official Google authorization. Complete eligibility setup in the official client first. See the [integration evidence](docs/evidence/development/subscription-antigravity-2026-09-30.md) for tested boundaries.

Current source changes are not necessarily included in the published v0.0.4 APKs.

## Your first task

1. **Connect a model.** Add your service under model settings and test the connection, or install a model from the local-model catalog. Online services may require your own API key and charge for usage.
2. **Choose a conversation model.** Start a conversation and select your configured model. Check local-model readiness before using it.
3. **Start small.** Try turning a few notes into a saved checklist.
4. **Check the result.** Review any approval request, inspect execution status, and open the generated file.

```text
Organize tomorrow's tasks: meeting at 10 a.m., mail a package in the afternoon,
and buy milk in the evening. Group them by morning, afternoon, and evening.
Save them as tomorrow.md. Do not invent times I did not give you.
Read the saved file back and check that it matches.
```

After a simple file task works, try a longer workflow. App-screen automation also requires the relevant Android permissions and authorization for the target app.

Share your use cases, successes, and problems in [Issues](https://github.com/dollarser/helix-agent/issues).

## Models and privacy

**Running actions on your phone does not mean all content stays on your phone.** When you use a cloud or self-hosted model, conversation content and relevant file excerpts or tool results are sent to that service. Choose services and access scopes appropriate for your data.

Local models run inference on the device, but downloads, browsing, and network tools may still use the internet. They also need storage and RAM. Speed and task quality vary by device; APKs do not include model weights.

File permissions, Android permissions, and conversation authorization serve different purposes. Grant only what a task needs. Follow the app's execution status for approval, refusal, and uncertain outcomes. Stopping a task does not undo external actions already completed.

## Troubleshooting

When filing an [issue](https://github.com/dollarser/helix-agent/issues), include the app version, package edition, device model, Android version, model name, reproduction steps, and error message.

Do not attach API keys, account credentials, or private files. Review and redact diagnostic information before sharing it.

## Contributing

Feedback and improvements are welcome. Before developing, read [AGENTS.md](AGENTS.md) and the [development setup](docs/development/environment.md).

- [Current status and known limitations](docs/development/status.md)
- [Roadmap](docs/development/roadmap.md)
- [Architecture and design decisions](docs/architecture/overview.md)
- [Documentation index](docs/README.md)

Code is licensed under [Apache-2.0](LICENSE). Third-party components retain their own licenses.
