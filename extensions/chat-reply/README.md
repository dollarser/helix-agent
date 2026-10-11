# Helix Chat Reply plugin

An independent Helix native plugin for user-requested analysis and drafting of the currently visible chat. It relies on the existing Mobile Use tools and the current Agent turn; it adds no accessibility service, background monitor, model transport, or send action.

The feature is developer-channel only for now, matching Mobile Use availability. Chat content remains high-sensitivity Accessibility-origin data and follows Helix's existing provider egress disclosure. The user must explicitly request insertion; sending remains manual.

## Status: prototype checkpoint

This module is not a completed automatic-reply feature. `chat.assist` currently returns workflow guidance; it does not extract a conversation or verify that the target chat is ready. `ChatConversation` and `NewIncomingMessageGate` have local unit-test coverage but are not connected to the production observation path. The gate uses message text and side, not stable message IDs, and is not suitable as an automatic-send trigger.

The bundled guidance still uses the existing native Skill injection path. [HXA-272](../../docs/development/tasks/HXA-272.md) and [HXA-273](../../docs/development/tasks/HXA-273.md) define the accepted replacement: independent Skill activation and trusted user/model invocation rules. This checkpoint does not implement those tasks. No end-to-end visible-chat, draft-insertion, background-monitoring or automatic-send acceptance is claimed; further work must define its target app and product scope. See the [workspace review](../../docs/evidence/development/workspace-submission-review-2026-10-11.md).

## Jev reference

The conversation-first workflow and bounded message interpretation were informed by [jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis), especially its adapter-to-neutral-conversation boundary and judge-before-draft flow. No Jev source code is copied into this module. Jev's MIT license and repository NOTICE are preserved in `LICENSE-JEV` and `NOTICE-JEV`; attribution is provided here. The original NOTICE requests preserving both files when distributing and attribution in documentation.
