---
name: browser
description: Read and interact with authorized webpages in the Helix browser
---
# Browser workflow

Read references/browser-workflow.md through skills.read_resource with this exact snapshotHash for navigation, content, image and failure rules.

1. Identify a current session tab with browser.tabs, or open the authorized target. For public web queries, use browser.search with an explicit bing, google or duckduckgo engine; it opens a search page, not API results.
2. Read browser.snapshot after loading. Inspect prose text/textTruncated separately from actionable nodes/truncated; browser.find and browser.wait match semantic nodes, not full page prose.
3. Resolve a unique current target, act and inspect the outcome. Use bounded PRESENT/ABSENT waits on settled pages when needed; re-observe after navigation.
4. Verify the requested page result and any saved artifacts before reporting completion.

Discover missing schemas before calling. This guide does not grant permission.
