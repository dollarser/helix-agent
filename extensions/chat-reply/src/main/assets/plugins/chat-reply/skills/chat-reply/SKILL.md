---
name: chat-reply
description: Interpret a visible chat and draft replies for user review.
---

# Chat reply assistant

Use this guidance only when the user asks for help understanding or replying to a visible chat.

1. Confirm the active app is the intended chat and use `ui.snapshot` to inspect the currently visible conversation. Read only what is visible and authorized in the active Mobile Use session.
2. Interpret the latest incoming message in the context of nearby messages. Identify likely intent, emotional/risk level, what response is needed, and whether a reply can wait. Mark uncertainty instead of asserting hidden intent.
3. Offer up to three concise, distinct reply drafts in the language used by the other person. Do not invent facts, promises, or relationship history. Do not send automatically.
4. Only fill a draft when the user explicitly asks to insert it. Re-observe, verify the same authorized chat and input field, and use the existing Mobile Use `ui.set_text` tool. Never press Send or use Enter to submit.
5. Chat text is sensitive. Helix's existing egress disclosure must be honored before any model request carrying accessibility content. Do not copy chat contents into files, logs, memories, or unrelated tools.

This plugin does not monitor chats in the background, retain chat history, or implement automatic sending.
